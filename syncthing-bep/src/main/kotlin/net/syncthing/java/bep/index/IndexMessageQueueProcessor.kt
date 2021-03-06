/*
 * Copyright (C) 2016 Davide Imbriaco
 * Copyright (C) 2018 Jonas Lochmann
 *
 * This Java file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.syncthing.java.bep.index

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.syncthing.java.bep.BlockExchangeProtos
import net.syncthing.java.bep.connectionactor.ClusterConfigInfo
import net.syncthing.java.core.beans.DeviceId
import net.syncthing.java.core.exception.ExceptionReport
import net.syncthing.java.core.exception.reportExceptions
import net.syncthing.java.core.interfaces.IndexRepository
import net.syncthing.java.core.interfaces.IndexTransaction
import net.syncthing.java.core.interfaces.TempRepository
import org.slf4j.LoggerFactory

class IndexMessageQueueProcessor (
        private val indexRepository: IndexRepository,
        private val tempRepository: TempRepository,
        private val onIndexRecordAcquiredEvents: BroadcastChannel<IndexInfoUpdateEvent>,
        private val onFullIndexAcquiredEvents: BroadcastChannel<String>,
        private val onFolderStatsUpdatedEvents: BroadcastChannel<FolderStatsChangedEvent>,
        private val isRemoteIndexAcquired: (ClusterConfigInfo, DeviceId, IndexTransaction) -> Boolean,
        exceptionReportHandler: (ExceptionReport) -> Unit
) {
    private data class IndexUpdateAction(val update: BlockExchangeProtos.IndexUpdate, val clusterConfigInfo: ClusterConfigInfo, val peerDeviceId: DeviceId)
    private data class StoredIndexUpdateAction(val updateId: String, val clusterConfigInfo: ClusterConfigInfo, val peerDeviceId: DeviceId)

    companion object {
        private val logger = LoggerFactory.getLogger(IndexMessageQueueProcessor::class.java)
        private const val BATCH_SIZE = 128
    }

    private val job = Job()
    private val indexUpdateIncomingLock = Mutex()
    private val indexUpdateProcessStoredQueue = Channel<StoredIndexUpdateAction>(capacity = Channel.UNLIMITED)
    private val indexUpdateProcessingQueue = Channel<IndexUpdateAction>(capacity = Channel.RENDEZVOUS)

    suspend fun handleIndexMessageReceivedEvent(folderId: String, filesList: List<BlockExchangeProtos.FileInfo>, clusterConfigInfo: ClusterConfigInfo, peerDeviceId: DeviceId) {
        filesList.chunked(BATCH_SIZE).forEach { chunck ->
            handleIndexMessageReceivedEventWithoutChuncking(folderId, chunck, clusterConfigInfo, peerDeviceId)
        }
    }

    suspend fun handleIndexMessageReceivedEventWithoutChuncking(folderId: String, filesList: List<BlockExchangeProtos.FileInfo>, clusterConfigInfo: ClusterConfigInfo, peerDeviceId: DeviceId) {
        indexUpdateIncomingLock.withLock {
            logger.info("received index message event, preparing")

            val data = BlockExchangeProtos.IndexUpdate.newBuilder()
                    .addAllFiles(filesList)
                    .setFolder(folderId)
                    .build()

            if (indexUpdateProcessingQueue.offer(IndexUpdateAction(data, clusterConfigInfo, peerDeviceId))) {
                // message is beeing processed now
            } else {
                val key = tempRepository.pushTempData(data.toByteArray())

                logger.debug("received index message event, stored to temp record {}, queuing for processing", key)
                indexUpdateProcessStoredQueue.send(StoredIndexUpdateAction(key, clusterConfigInfo, peerDeviceId))
            }
        }
    }

    init {
        GlobalScope.async(Dispatchers.IO + job) {
            indexUpdateProcessingQueue.consumeEach {
                try {
                    doHandleIndexMessageReceivedEvent(it)
                } catch (ex: IndexMessageProcessor.IndexInfoNotFoundException) {
                    // ignored
                    // this is expected when the data is deleted but some index updates are still in the queue

                    logger.warn("could not find index info for index update")
                }
            }
        }.reportExceptions("IndexMessageQueueProcessor.indexUpdateProcessingQueue", exceptionReportHandler)

        GlobalScope.async(Dispatchers.IO + job) {
            indexUpdateProcessStoredQueue.consumeEach { action ->
                logger.debug("processing index message event from temp record {}", action.updateId)

                val data = tempRepository.popTempData(action.updateId)
                val message = BlockExchangeProtos.IndexUpdate.parseFrom(data)

                indexUpdateProcessingQueue.send(IndexUpdateAction(
                        message,
                        action.clusterConfigInfo,
                        action.peerDeviceId
                ))
            }
        }.reportExceptions("IndexMessageQueueProcessor.indexUpdateProcessStoredQueue", exceptionReportHandler)
    }

    private suspend fun doHandleIndexMessageReceivedEvent(action: IndexUpdateAction) {
        val (message, clusterConfigInfo, peerDeviceId) = action

        val folderInfo = clusterConfigInfo.folderInfoById[message.folder]
                ?: throw IllegalStateException("got folder info for folder without known folder info")

        if (!folderInfo.isDeviceInSharedFolderWhitelist) {
            throw IllegalStateException("received index update for folder which is not shared")
        }

        logger.info("processing index message with {} records", message.filesCount)

        val (indexResult, wasIndexAcquired) = indexRepository.runInTransaction { indexTransaction ->
            val wasIndexAcquiredBefore = isRemoteIndexAcquired(clusterConfigInfo, peerDeviceId, indexTransaction)

            val startTime = System.currentTimeMillis()

            val indexResult = IndexMessageProcessor.doHandleIndexMessageReceivedEvent(
                    message = message,
                    peerDeviceId = peerDeviceId,
                    transaction = indexTransaction
            )

            val endTime = System.currentTimeMillis()

            logger.info("processed {} index records, acquired {} in ${endTime - startTime} ms", message.filesCount, indexResult.updatedFiles.size)

            logger.debug("index info = {}", indexResult.newIndexInfo)

            indexResult to ((!wasIndexAcquiredBefore) && isRemoteIndexAcquired(clusterConfigInfo, peerDeviceId, indexTransaction))
        }

        if (indexResult.updatedFiles.isNotEmpty()) {
            onIndexRecordAcquiredEvents.send(IndexRecordAcquiredEvent(message.folder, indexResult.updatedFiles, indexResult.newIndexInfo))
        }

        onFolderStatsUpdatedEvents.send(FolderStatsUpdatedEvent(indexResult.newFolderStats))

        if (wasIndexAcquired) {
            logger.debug("index acquired")
            onFullIndexAcquiredEvents.send(message.folder)
        }
    }

    fun stop() {
        logger.info("stopping index record processor")
        job.cancel()
    }
}
