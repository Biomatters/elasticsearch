/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.metadata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexClusterStateUpdateRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.RestoreInProgress;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.index.Index;
import org.elasticsearch.snapshots.RestoreService;
import org.elasticsearch.snapshots.SnapshotInProgressException;
import org.elasticsearch.snapshots.SnapshotsService;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Deletes indices.
 */
public class MetadataDeleteIndexService {

    private static final Logger logger = LogManager.getLogger(MetadataDeleteIndexService.class);

    private final Settings settings;
    private final ClusterService clusterService;

    private final AllocationService allocationService;

    @Inject
    public MetadataDeleteIndexService(Settings settings, ClusterService clusterService, AllocationService allocationService) {
        this.settings = settings;
        this.clusterService = clusterService;
        this.allocationService = allocationService;
    }

    public void deleteIndices(final DeleteIndexClusterStateUpdateRequest request, final ActionListener<AcknowledgedResponse> listener) {
        if (request.indices() == null || request.indices().length == 0) {
            throw new IllegalArgumentException("Index name is required");
        }

        submitUnbatchedTask(
            "delete-index " + Arrays.toString(request.indices()),
            new AckedClusterStateUpdateTask(Priority.URGENT, request, listener) {
                @Override
                public ClusterState execute(final ClusterState currentState) {
                    return deleteIndices(currentState, Sets.newHashSet(request.indices()));
                }
            }
        );
    }

    @SuppressForbidden(reason = "legacy usage of unbatched task") // TODO add support for batching here
    private void submitUnbatchedTask(@SuppressWarnings("SameParameterValue") String source, ClusterStateUpdateTask task) {
        clusterService.submitUnbatchedStateUpdateTask(source, task);
    }

    /**
     * Delete some indices from the cluster state.
     */
    public ClusterState deleteIndices(ClusterState currentState, Set<Index> indices) {
        final Metadata meta = currentState.metadata();
        final Set<Index> indicesToDelete = new HashSet<>();
        final Map<Index, DataStream> backingIndices = new HashMap<>();
        for (Index index : indices) {
            IndexMetadata im = meta.getIndexSafe(index);
            IndexAbstraction.DataStream parent = meta.getIndicesLookup().get(im.getIndex().getName()).getParentDataStream();
            if (parent != null) {
                if (parent.getWriteIndex().equals(im.getIndex())) {
                    throw new IllegalArgumentException(
                        "index ["
                            + index.getName()
                            + "] is the write index for data stream ["
                            + parent.getName()
                            + "] and cannot be deleted"
                    );
                } else {
                    backingIndices.put(index, parent.getDataStream());
                }
            }
            indicesToDelete.add(im.getIndex());
        }

        // Check if index deletion conflicts with any running snapshots
        Set<Index> snapshottingIndices = SnapshotsService.snapshottingIndices(currentState, indicesToDelete);
        if (snapshottingIndices.isEmpty() == false) {
            throw new SnapshotInProgressException(
                "Cannot delete indices that are being snapshotted: "
                    + snapshottingIndices
                    + ". Try again after snapshot finishes or cancel the currently running snapshot."
            );
        }

        RoutingTable.Builder routingTableBuilder = RoutingTable.builder(currentState.routingTable());
        Metadata.Builder metadataBuilder = Metadata.builder(meta);
        ClusterBlocks.Builder clusterBlocksBuilder = ClusterBlocks.builder().blocks(currentState.blocks());

        final IndexGraveyard.Builder graveyardBuilder = IndexGraveyard.builder(metadataBuilder.indexGraveyard());
        final int previousGraveyardSize = graveyardBuilder.tombstones().size();
        for (final Index index : indices) {
            String indexName = index.getName();
            logger.info("{} deleting index", index);
            routingTableBuilder.remove(indexName);
            clusterBlocksBuilder.removeIndexBlocks(indexName);
            metadataBuilder.remove(indexName);
            if (backingIndices.containsKey(index)) {
                DataStream parent = metadataBuilder.dataStream(backingIndices.get(index).getName());
                metadataBuilder.put(parent.removeBackingIndex(index));
            }
        }
        // add tombstones to the cluster state for each deleted index
        final IndexGraveyard currentGraveyard = graveyardBuilder.addTombstones(indices).build(settings);
        metadataBuilder.indexGraveyard(currentGraveyard); // the new graveyard set on the metadata
        logger.trace(
            "{} tombstones purged from the cluster state. Previous tombstone size: {}. Current tombstone size: {}.",
            graveyardBuilder.getNumPurged(),
            previousGraveyardSize,
            currentGraveyard.getTombstones().size()
        );

        Metadata newMetadata = metadataBuilder.build();
        ClusterBlocks blocks = clusterBlocksBuilder.build();

        // update snapshot restore entries
        ImmutableOpenMap<String, ClusterState.Custom> customs = currentState.getCustoms();
        final RestoreInProgress restoreInProgress = currentState.custom(RestoreInProgress.TYPE, RestoreInProgress.EMPTY);
        RestoreInProgress updatedRestoreInProgress = RestoreService.updateRestoreStateWithDeletedIndices(restoreInProgress, indices);
        if (updatedRestoreInProgress != restoreInProgress) {
            ImmutableOpenMap.Builder<String, ClusterState.Custom> builder = ImmutableOpenMap.builder(customs);
            builder.put(RestoreInProgress.TYPE, updatedRestoreInProgress);
            customs = builder.build();
        }

        return allocationService.reroute(
            ClusterState.builder(currentState)
                .routingTable(routingTableBuilder.build())
                .metadata(newMetadata)
                .blocks(blocks)
                .customs(customs)
                .build(),
            "deleted indices [" + indices + "]"
        );
    }
}
