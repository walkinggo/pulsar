/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.persistent;

import static org.apache.pulsar.common.util.Runnables.catchingAndLoggingThrowables;
import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.io.IOException;
import java.time.Clock;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.PositionFactory;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.pulsar.broker.service.Replicator;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.stats.OpenTelemetryReplicatedSubscriptionStats;
import org.apache.pulsar.common.api.proto.ClusterMessageId;
import org.apache.pulsar.common.api.proto.CommandAck.AckType;
import org.apache.pulsar.common.api.proto.CommandSubscribe.InitialPosition;
import org.apache.pulsar.common.api.proto.MarkerType;
import org.apache.pulsar.common.api.proto.MarkersMessageIdData;
import org.apache.pulsar.common.api.proto.ReplicatedSubscriptionsSnapshot;
import org.apache.pulsar.common.api.proto.ReplicatedSubscriptionsSnapshotRequest;
import org.apache.pulsar.common.api.proto.ReplicatedSubscriptionsSnapshotResponse;
import org.apache.pulsar.common.api.proto.ReplicatedSubscriptionsUpdate;
import org.apache.pulsar.common.protocol.Markers;
import org.apache.pulsar.opentelemetry.annotations.PulsarDeprecatedMetric;

/**
 * Encapsulate all the logic of replicated subscriptions tracking for a given topic.
 */
@Slf4j
public class ReplicatedSubscriptionsController implements AutoCloseable, Topic.PublishContext {
    private final PersistentTopic topic;
    private final String localCluster;

    // The timestamp of when the last snapshot was initiated
    private volatile long lastCompletedSnapshotStartTime = 0;

    private String lastCompletedSnapshotId;

    private volatile Position positionOfLastLocalMarker;

    private final ScheduledFuture<?> timer;

    private final ConcurrentMap<String, ReplicatedSubscriptionsSnapshotBuilder> pendingSnapshots =
            new ConcurrentHashMap<>();

    @PulsarDeprecatedMetric(
            newMetricName = OpenTelemetryReplicatedSubscriptionStats.SNAPSHOT_OPERATION_COUNT_METRIC_NAME)
    @Deprecated
    private static final Gauge pendingSnapshotsMetric = Gauge
            .build("pulsar_replicated_subscriptions_pending_snapshots",
                    "Counter of currently pending snapshots")
            .register();

    // timeouts use SnapshotOperationResult.TIMEOUT.attributes on the same metric
    @PulsarDeprecatedMetric(
            newMetricName = OpenTelemetryReplicatedSubscriptionStats.SNAPSHOT_OPERATION_COUNT_METRIC_NAME)
    @Deprecated
    private static final Counter timedoutSnapshotsMetric = Counter
            .build().name("pulsar_replicated_subscriptions_timedout_snapshots")
            .help("Counter of timed out snapshots").register();

    private final OpenTelemetryReplicatedSubscriptionStats stats;

    public ReplicatedSubscriptionsController(PersistentTopic topic, String localCluster) {
        this.topic = topic;
        this.localCluster = localCluster;
        var pulsar = topic.getBrokerService().pulsar();
        timer = pulsar.getExecutor()
                .scheduleAtFixedRate(catchingAndLoggingThrowables(this::startNewSnapshot), 0,
                        pulsar.getConfiguration().getReplicatedSubscriptionsSnapshotFrequencyMillis(),
                        TimeUnit.MILLISECONDS);
        stats = pulsar.getOpenTelemetryReplicatedSubscriptionStats();
    }

    public void receivedReplicatedSubscriptionMarker(Position position, int markerType, ByteBuf payload) {
        try {
            switch (markerType) {
            case MarkerType.REPLICATED_SUBSCRIPTION_SNAPSHOT_REQUEST_VALUE:
                receivedSnapshotRequest(Markers.parseReplicatedSubscriptionsSnapshotRequest(payload));
                break;

            case MarkerType.REPLICATED_SUBSCRIPTION_SNAPSHOT_RESPONSE_VALUE:
                receivedSnapshotResponse(position, Markers.parseReplicatedSubscriptionsSnapshotResponse(payload));
                break;

            case MarkerType.REPLICATED_SUBSCRIPTION_UPDATE_VALUE:
                receiveSubscriptionUpdated(Markers.parseReplicatedSubscriptionsUpdate(payload));
                break;

            default:
                // Ignore
            }
        } catch (IOException e) {
            log.warn("[{}] Failed to parse marker: {}", topic.getName(), e);
        }
    }

    public void localSubscriptionUpdated(String subscriptionName, ReplicatedSubscriptionsSnapshot snapshot) {
        if (log.isDebugEnabled()) {
            log.debug("[{}][{}] Updating subscription to snapshot {}", topic, subscriptionName,
                    snapshot.getClustersList().stream()
                            .map(cmid -> String.format("%s -> %d:%d", cmid.getCluster(),
                                    cmid.getMessageId().getLedgerId(), cmid.getMessageId().getEntryId()))
                            .collect(Collectors.toList()));
        }

        Map<String, MarkersMessageIdData> clusterIds = new TreeMap<>();
        for (int i = 0, size = snapshot.getClustersCount(); i < size; i++) {
            ClusterMessageId cmid = snapshot.getClusterAt(i);
            clusterIds.put(cmid.getCluster(), cmid.getMessageId());
        }

        ByteBuf subscriptionUpdate = Markers.newReplicatedSubscriptionsUpdate(subscriptionName, clusterIds);
        writeMarker(subscriptionUpdate);
    }

    private void receivedSnapshotRequest(ReplicatedSubscriptionsSnapshotRequest request) {
        // if replicator producer is already closed, restart it to send snapshot response
        Replicator replicator = topic.getReplicators().get(request.getSourceCluster());
        if (!replicator.isConnected()) {
            topic.startReplProducers();
        }

        // Send response containing the current last written message id. The response
        // marker we're publishing locally and then replicating will have a higher
        // message id.
        Position lastMsgId = topic.getLastPosition();
        if (log.isDebugEnabled()) {
            log.debug("[{}] Received snapshot request. Last msg id: {}", topic.getName(), lastMsgId);
        }

        ByteBuf marker = Markers.newReplicatedSubscriptionsSnapshotResponse(
                request.getSnapshotId(),
                request.getSourceCluster(),
                localCluster,
                lastMsgId.getLedgerId(), lastMsgId.getEntryId());
        writeMarker(marker);
    }

    private void receivedSnapshotResponse(Position position, ReplicatedSubscriptionsSnapshotResponse response) {
        String snapshotId = response.getSnapshotId();
        ReplicatedSubscriptionsSnapshotBuilder builder = pendingSnapshots.get(snapshotId);
        if (builder == null) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Received late reply for timed-out snapshot {} from {}", topic.getName(), snapshotId,
                        response.getCluster().getCluster());
            }
            return;
        }

        builder.receivedSnapshotResponse(position, response);
    }

    private void receiveSubscriptionUpdated(ReplicatedSubscriptionsUpdate update) {
        MarkersMessageIdData updatedMessageId = null;
        for (int i = 0, size = update.getClustersCount(); i < size; i++) {
            ClusterMessageId cmid = update.getClusterAt(i);
            if (localCluster.equals(cmid.getCluster())) {
                updatedMessageId = cmid.getMessageId();
            }
        }

        if (updatedMessageId == null) {
            // No updates for this cluster, ignore
            return;
        }

        Position pos = PositionFactory.create(updatedMessageId.getLedgerId(), updatedMessageId.getEntryId());

        if (log.isDebugEnabled()) {
            log.debug("[{}][{}] Received update for subscription to {}", topic, update.getSubscriptionName(), pos);
        }

        PersistentSubscription sub = topic.getSubscription(update.getSubscriptionName());
        if (sub != null) {
            sub.acknowledgeMessage(Collections.singletonList(pos), AckType.Cumulative, Collections.emptyMap());
        } else {
            // Subscription doesn't exist. We need to force the creation of the subscription in this cluster.
            log.info("[{}][{}] Creating subscription at {}:{} after receiving update from replicated subscription",
                    topic, update.getSubscriptionName(), updatedMessageId.getLedgerId(), pos);
            topic.createSubscription(update.getSubscriptionName(), InitialPosition.Earliest,
                            true /* replicateSubscriptionState */, Collections.emptyMap())
                    .thenAccept(subscriptionCreated -> {
                        subscriptionCreated.acknowledgeMessage(Collections.singletonList(pos),
                                AckType.Cumulative, Collections.emptyMap());
                    });
        }
    }

    private void startNewSnapshot() {
        cleanupTimedOutSnapshots();

        if (lastCompletedSnapshotStartTime == 0 && !pendingSnapshots.isEmpty()) {
            // 1. If the remote cluster has disabled subscription replication or there's an incorrect config,
            //    it will not respond to SNAPSHOT_REQUEST. Therefore, lastCompletedSnapshotStartTime will remain 0,
            //    making it unnecessary to resend the request.
            // 2. This approach prevents sending additional SNAPSHOT_REQUEST to both local_topic and remote_topic.
            // 3. Since it's uncertain when the remote cluster will enable subscription replication,
            //    the timeout mechanism of pendingSnapshots is used to ensure retries.
            //
            // In other words, when hit this case, The frequency of sending SNAPSHOT_REQUEST
            // will use `replicatedSubscriptionsSnapshotTimeoutSeconds`.
            if (log.isDebugEnabled()) {
                log.debug("[{}] PendingSnapshot exists but has never succeeded. "
                        + "Skipping snapshot creation until pending snapshot timeout.", topic.getName());
            }
            return;
        }

        if (topic.getLastMaxReadPositionMovedForwardTimestamp() < lastCompletedSnapshotStartTime
                || topic.getLastMaxReadPositionMovedForwardTimestamp() == 0) {
            // There was no message written since the last snapshot, we can skip creating a new snapshot
            if (log.isDebugEnabled()) {
                log.debug("[{}] There is no new data in topic. Skipping snapshot creation.", topic.getName());
            }
            return;
        }

        MutableBoolean anyReplicatorDisconnected = new MutableBoolean();
        topic.getReplicators().forEach((cluster, replicator) -> {
            if (!replicator.isConnected()) {
                anyReplicatorDisconnected.setTrue();
            }
        });

        if (anyReplicatorDisconnected.isTrue()) {
            // Do not attempt to create snapshot when some of the clusters are not reachable
            if (log.isDebugEnabled()) {
                log.debug("[{}] Do not attempt to create snapshot when some of the clusters are not reachable.",
                        topic.getName());
            }
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}] Starting snapshot creation.", topic.getName());
        }

        pendingSnapshotsMetric.inc();
        stats.recordSnapshotStarted();
        ReplicatedSubscriptionsSnapshotBuilder builder = new ReplicatedSubscriptionsSnapshotBuilder(this,
                topic.getReplicators().keySet(), topic.getBrokerService().pulsar().getConfiguration(),
                Clock.systemUTC());
        pendingSnapshots.put(builder.getSnapshotId(), builder);
        builder.start();
    }

    public Optional<String> getLastCompletedSnapshotId() {
        return Optional.ofNullable(lastCompletedSnapshotId);
    }

    private void cleanupTimedOutSnapshots() {
        Iterator<Map.Entry<String, ReplicatedSubscriptionsSnapshotBuilder>> it = pendingSnapshots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ReplicatedSubscriptionsSnapshotBuilder> entry = it.next();
            if (entry.getValue().isTimedOut()) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Snapshot creation timed out for {}", topic.getName(), entry.getKey());
                }

                pendingSnapshotsMetric.dec();
                timedoutSnapshotsMetric.inc();
                var latencyMillis = entry.getValue().getDurationMillis();
                stats.recordSnapshotTimedOut(latencyMillis);
                it.remove();
            }
        }
    }

    void snapshotCompleted(String snapshotId) {
        ReplicatedSubscriptionsSnapshotBuilder snapshot = pendingSnapshots.remove(snapshotId);
        lastCompletedSnapshotId = snapshotId;

        if (snapshot != null) {
            lastCompletedSnapshotStartTime = snapshot.getStartTimeMillis();

            pendingSnapshotsMetric.dec();
            var latencyMillis = snapshot.getDurationMillis();
            ReplicatedSubscriptionsSnapshotBuilder.SNAPSHOT_METRIC.observe(latencyMillis);
            stats.recordSnapshotCompleted(latencyMillis);
        }
    }

    void writeMarker(ByteBuf marker) {
        try {
            topic.publishMessage(marker, this);
        } finally {
            marker.release();
        }
    }

    /**
     * From Topic.PublishContext.
     */
    @Override
    public void completed(Exception e, long ledgerId, long entryId) {
        // Nothing to do in case of publish errors since the retry logic is applied upstream after a snapshot is not
        // closed
        if (log.isDebugEnabled()) {
            log.debug("[{}] Published marker at {}:{}. Exception: {}", topic.getName(), ledgerId, entryId, e);
        }

        this.positionOfLastLocalMarker = PositionFactory.create(ledgerId, entryId);
    }

    PersistentTopic topic() {
        return topic;
    }

    String localCluster() {
        return localCluster;
    }

    @VisibleForTesting
    public ConcurrentMap<String, ReplicatedSubscriptionsSnapshotBuilder> pendingSnapshots() {
        return pendingSnapshots;
    }

    @Override
    public boolean isMarkerMessage() {
        // Everything published by this controller will be a marker a message
        return true;
    }

    @Override
    public void close() {
        timer.cancel(true);
    }
}
