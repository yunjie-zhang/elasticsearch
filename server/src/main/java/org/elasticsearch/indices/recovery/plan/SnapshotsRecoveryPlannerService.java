/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices.recovery.plan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.store.StoreFileMetadata;
import org.elasticsearch.indices.recovery.RecoverySettings;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.common.util.CollectionUtils.concatLists;
import static org.elasticsearch.indices.recovery.RecoverySettings.SEQ_NO_SNAPSHOT_RECOVERIES_SUPPORTED_VERSION;

public class SnapshotsRecoveryPlannerService implements RecoveryPlannerService {
    private final Logger logger = LogManager.getLogger(SnapshotsRecoveryPlannerService.class);

    private final ShardSnapshotsService shardSnapshotsService;

    public SnapshotsRecoveryPlannerService(ShardSnapshotsService shardSnapshotsService) {
        this.shardSnapshotsService = shardSnapshotsService;
    }

    public void computeRecoveryPlan(ShardId shardId,
                                    @Nullable String shardStateIdentifier,
                                    Store.MetadataSnapshot sourceMetadata,
                                    Store.MetadataSnapshot targetMetadata,
                                    long startingSeqNo,
                                    int translogOps,
                                    Version targetVersion,
                                    boolean useSnapshots,
                                    ActionListener<ShardRecoveryPlan> listener) {
        // Fallback to source only recovery if the target node is in an incompatible version
        boolean canUseSnapshots = useSnapshots && targetVersion.onOrAfter(RecoverySettings.SNAPSHOT_RECOVERIES_SUPPORTED_VERSION);

        fetchLatestSnapshotsIgnoringErrors(shardId, canUseSnapshots, latestSnapshotOpt ->
            ActionListener.completeWith(listener, () ->
                computeRecoveryPlanWithSnapshots(
                    shardStateIdentifier,
                    sourceMetadata,
                    targetMetadata,
                    startingSeqNo,
                    translogOps,
                    latestSnapshotOpt
                )
            )
        );
    }

    private ShardRecoveryPlan computeRecoveryPlanWithSnapshots(@Nullable String shardStateIdentifier,
                                                               Store.MetadataSnapshot sourceMetadata,
                                                               Store.MetadataSnapshot targetMetadata,
                                                               long startingSeqNo,
                                                               int translogOps,
                                                               Optional<ShardSnapshot> latestSnapshotOpt) {
        Store.RecoveryDiff sourceTargetDiff = sourceMetadata.recoveryDiff(targetMetadata);
        List<StoreFileMetadata> filesMissingInTarget = concatLists(sourceTargetDiff.missing, sourceTargetDiff.different);

        if (latestSnapshotOpt.isEmpty()) {
            // If we couldn't find any valid  snapshots, fallback to the source
            return getRecoveryPlanUsingSourceNode(sourceMetadata, sourceTargetDiff, filesMissingInTarget, startingSeqNo, translogOps);
        }

        ShardSnapshot latestSnapshot = latestSnapshotOpt.get();

        // Primary failed over after the snapshot was taken
        if (latestSnapshot.isLogicallyEquivalent(shardStateIdentifier) &&
            latestSnapshot.hasDifferentPhysicalFiles(sourceMetadata) &&
            isSnapshotVersionCompatible(latestSnapshot) &&
            sourceTargetDiff.identical.isEmpty()) {
            // Use the current primary as a fallback if the download fails half-way
            ShardRecoveryPlan fallbackPlan =
                getRecoveryPlanUsingSourceNode(sourceMetadata, sourceTargetDiff, filesMissingInTarget, startingSeqNo, translogOps);

            ShardRecoveryPlan.SnapshotFilesToRecover snapshotFilesToRecover = new ShardRecoveryPlan.SnapshotFilesToRecover(
                latestSnapshot.getIndexId(),
                latestSnapshot.getRepository(),
                latestSnapshot.getSnapshotFiles()
            );

            return new ShardRecoveryPlan(snapshotFilesToRecover,
                Collections.emptyList(),
                Collections.emptyList(),
                startingSeqNo,
                translogOps,
                latestSnapshot.getMetadataSnapshot(),
                fallbackPlan
            );
        }

        Store.MetadataSnapshot filesToRecoverFromSourceSnapshot = toMetadataSnapshot(filesMissingInTarget);
        Store.RecoveryDiff snapshotDiff = filesToRecoverFromSourceSnapshot.recoveryDiff(latestSnapshot.getMetadataSnapshot());
        final ShardRecoveryPlan.SnapshotFilesToRecover snapshotFilesToRecover;
        if (snapshotDiff.identical.isEmpty()) {
            snapshotFilesToRecover = ShardRecoveryPlan.SnapshotFilesToRecover.EMPTY;
        } else {
            snapshotFilesToRecover = new ShardRecoveryPlan.SnapshotFilesToRecover(latestSnapshot.getIndexId(),
                latestSnapshot.getRepository(),
                latestSnapshot.getSnapshotFilesMatching(snapshotDiff.identical));
        }

        return new ShardRecoveryPlan(snapshotFilesToRecover,
            concatLists(snapshotDiff.missing, snapshotDiff.different),
            sourceTargetDiff.identical,
            startingSeqNo,
            translogOps,
            sourceMetadata
        );
    }

    private boolean isSnapshotVersionCompatible(ShardSnapshot snapshot) {
        Version commitVersion = snapshot.getCommitVersion();
        // if the snapshotVersion == null that means that the snapshot was taken in a version <= 7.15,
        // therefore we can safely use that snapshot. Since this runs on the shard primary and
        // NodeVersionAllocationDecider ensures that we only recover to a node that has newer or
        // same version.
        if (commitVersion == null) {
            assert SEQ_NO_SNAPSHOT_RECOVERIES_SUPPORTED_VERSION.luceneVersion.onOrAfter(snapshot.getCommitLuceneVersion());
            return Version.CURRENT.luceneVersion.onOrAfter(snapshot.getCommitLuceneVersion());
        }
        return commitVersion.onOrBefore(Version.CURRENT);
    }

    private ShardRecoveryPlan getRecoveryPlanUsingSourceNode(Store.MetadataSnapshot sourceMetadata,
                                                             Store.RecoveryDiff sourceTargetDiff,
                                                             List<StoreFileMetadata> filesMissingInTarget,
                                                             long startingSeqNo,
                                                             int translogOps) {
        return new ShardRecoveryPlan(ShardRecoveryPlan.SnapshotFilesToRecover.EMPTY,
            filesMissingInTarget,
            sourceTargetDiff.identical,
            startingSeqNo,
            translogOps,
            sourceMetadata
        );
    }

    private void fetchLatestSnapshotsIgnoringErrors(ShardId shardId, boolean useSnapshots, Consumer<Optional<ShardSnapshot>> listener) {
        if (useSnapshots == false) {
            listener.accept(Optional.empty());
            return;
        }

        ActionListener<Optional<ShardSnapshot>> listenerIgnoringErrors = new ActionListener<>() {
            @Override
            public void onResponse(Optional<ShardSnapshot> shardSnapshotData) {
                listener.accept(shardSnapshotData);
            }

            @Override
            public void onFailure(Exception e) {
                logger.warn(new ParameterizedMessage("Unable to fetch available snapshots for shard {}", shardId), e);
                listener.accept(Optional.empty());
            }
        };

        shardSnapshotsService.fetchLatestSnapshotsForShard(shardId, listenerIgnoringErrors);
    }

    private Store.MetadataSnapshot toMetadataSnapshot(List<StoreFileMetadata> files) {
        return new Store.MetadataSnapshot(
            files
                .stream()
                .collect(Collectors.toMap(StoreFileMetadata::name, Function.identity())),
            emptyMap(),
            0
        );
    }
}
