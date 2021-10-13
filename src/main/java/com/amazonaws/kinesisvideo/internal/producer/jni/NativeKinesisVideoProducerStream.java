package com.amazonaws.kinesisvideo.internal.producer.jni;

import org.apache.logging.log4j.Logger;
import com.amazonaws.kinesisvideo.common.preconditions.Preconditions;
import com.amazonaws.kinesisvideo.producer.FrameFlags;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFragmentAck;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.internal.producer.KinesisVideoMetrics;
import com.amazonaws.kinesisvideo.internal.producer.KinesisVideoProducerStream;
import com.amazonaws.kinesisvideo.internal.producer.KinesisVideoStreamMetrics;
import com.amazonaws.kinesisvideo.producer.ProducerException;
import com.amazonaws.kinesisvideo.internal.producer.ReadResult;
import com.amazonaws.kinesisvideo.producer.StreamCallbacks;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.kinesisvideo.producer.DeviceInfo;
import com.amazonaws.kinesisvideo.producer.Time;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link KinesisVideoProducerStream}
 *
 *
 */
public class NativeKinesisVideoProducerStream implements KinesisVideoProducerStream
{
    final static long TIMEOUT_IN_MS = 30000; // 30 seconds
    private class NativeDataInputStream extends InputStream {
        /**
         * Whether the stream has been closed
         */
        private volatile boolean mStreamClosed = false;

        // Set the notification values
        private final Object mMonitor = new Object();
        private boolean mDataAvailable = false;
        private long mAvailableDataSize = 0;
        private final ReadResult mReadResult;
        final long mUploadHandle;

        public NativeDataInputStream(final long uploadHandle) {
            mUploadHandle = uploadHandle;
            mReadResult = new ReadResult();
        }

        @Override
        public int read() throws IOException
        {
            throw new IOException("Can't call byte-by-byte");
        }

        @Override
        public int read(final byte[] b,
                        final int off,
                        final int len)
                throws IOException {
            if (mStreamClosed) {
                mLogger.warn("Stream {} with uploadHandle {} has been closed", mStreamInfo.getName(), mUploadHandle);
            }

            // Read from the KinesisVideo Producer
            // NOTE: This implementation is a blocking call and the blocking
            // is handled by simply spin-locking until the data is available.
            int bytesRead = -1;

            while (!mStreamClosed) {
                synchronized (mMonitor) {
                    while (!mDataAvailable && !mStreamClosed) {
                        try {
                            mLogger.debug("no data for stream {} with uploadHandle {}, waiting", mStreamInfo.getName(),
                                    mUploadHandle);
                            mMonitor.wait(TIMEOUT_IN_MS);
                        } catch (final InterruptedException e) {
                            mLogger.error("Waiting for the data availability with uploadHandle {}", mUploadHandle);
                            mLogger.error(e);
                        }
                    }

                    // Clear the availability indicator for now
                    mDataAvailable = false;
                    if (mStreamClosed) {
                        // Indicate the EOS
                        bytesRead = -1;
                        mLogger.debug("Being notified to close stream {} with uploadHandle {}",
                                mStreamInfo.getName(), mUploadHandle);
                        return bytesRead;
                    }
                }

                try {
                    mKinesisVideoProducerJni.getStreamData(mStreamHandle, mUploadHandle, b, off, len, mReadResult);
                    bytesRead = mReadResult.getReadBytes();
                    mLogger.debug("getStreamData fill {} bytes for stream {} with uploadHandle {}", bytesRead,
                            mStreamInfo.getName(), mUploadHandle);

                    if (mReadResult.isEndOfStream()) {
                        // EOS for current session
                        mLogger.info("Received end-of-stream indicator for {}, uploadHandle {}",
                                mStreamInfo.getName(), mUploadHandle);

                        // Set the flag so the stream is not valid any longer
                        mStreamClosed = true;

                        if (0 == bytesRead) {
                            // Indicate the EOS
                            bytesRead = -1;
                        }
                    }

                    synchronized (mMonitor) {
                        if (bytesRead != 0) {
                            // Got some bytes - break from the loop.
                            // Make sure we don't await again if we still have some data
                            if (bytesRead != -1 && mAvailableDataSize - bytesRead > 0) {
                                mDataAvailable = true;
                            }

                            break;
                        }
                    }
                } catch (final ProducerException e) {
                    mLogger.error("Reader threw an exception", e);
                    throw new IOException(e);
                }
            }

            mLogger.debug("Streamed {} bytes for stream {} with uploadHandle {}", bytesRead, mStreamInfo.getName(),
                    mUploadHandle);

            if (-1 == bytesRead) {
                mLogger.debug("Closing stream {} with uploadHandle {}", mStreamInfo.getName(), mUploadHandle);
            }

            return bytesRead;
        }

        @Override
        public int read(final byte[] b)
                throws IOException
        {
            return read(b, 0, b.length);
        }

        @Override
        public void close()
                throws IOException
        {
            // Set the stream to stopped state
            mStreamClosed = true;

            // Notify the awaiting thread
            notifyReaderThread(0, 0);
        }

        protected void notifyReaderThread(final long duration, final long availableSize) {
            // Unblock the awaiting reading code block
            synchronized (mMonitor) {
                mAvailableDataSize = availableSize;
                mLogger.debug("Data availability notification. Upload handle: {}, Size: {}, Duration {} ",
                        mUploadHandle, availableSize, duration);

                mDataAvailable = true;

                mMonitor.notify();
            }
        }

        protected void endOfReaderThread() {
            // Unblock the awaiting reading code block
            synchronized (mMonitor) {
                mDataAvailable = true;
                mStreamClosed = true;
                mMonitor.notify();
            }
        }
    }

    private static final int SERVICE_CALL_RESULT_OK = 200;
    private final NativeKinesisVideoProducerJni mKinesisVideoProducerJni;
    private volatile long mStreamHandle;
    private final StreamInfo mStreamInfo;
    private final DeviceInfo mDeviceInfo;
    private final StreamCallbacks mStreamCallbacks;
    private final CountDownLatch mReadyLatch;
    private final CountDownLatch mStoppedLatch;
    private final Logger mLogger;
    private final KinesisVideoStreamMetrics mStreamMetrics;
    private final Map<Long, NativeDataInputStream> mInputStreamMap;

    public NativeKinesisVideoProducerStream(final @Nonnull NativeKinesisVideoProducerJni kinesisVideoProducerJni,
                                            final @Nonnull StreamInfo streamInfo,
                                            final long streamHandle,
                                            final @Nonnull Logger logger,
                                            final @Nullable StreamCallbacks streamCallbacks, 
                                            final @Nullable DeviceInfo deviceInfo) {
        mKinesisVideoProducerJni = Preconditions.checkNotNull(kinesisVideoProducerJni);
        mStreamInfo = Preconditions.checkNotNull(streamInfo);
        Preconditions.checkState(streamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);
        mStreamHandle = streamHandle;
        mStreamCallbacks = streamCallbacks;
        mReadyLatch = new CountDownLatch(1);
        mStoppedLatch = new CountDownLatch(1);
        mLogger = Preconditions.checkNotNull(logger);
        mStreamMetrics = new KinesisVideoStreamMetrics();
        mInputStreamMap = new HashMap<Long, NativeDataInputStream>();
        mDeviceInfo = deviceInfo;
    }

    @Override
    public InputStream getDataStream(final long uploadHandle) throws ProducerException {
        final NativeDataInputStream inputStream = new NativeDataInputStream(uploadHandle);
        mInputStreamMap.put(uploadHandle, inputStream);
        return inputStream;
    }

    @Override
    public void getStreamData(final long uploadHandle,
                              @Nonnull final byte[] fillBuffer,
                              final int offset,
                              final int length,
                              @Nonnull final ReadResult readResult) throws ProducerException {
        Preconditions.checkState(mStreamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);
        mKinesisVideoProducerJni.getStreamData(mStreamHandle, uploadHandle, fillBuffer, offset, length, readResult);
    }

    @Override
    public void putFrame(@Nonnull final KinesisVideoFrame kinesisVideoFrame) throws ProducerException {
        Preconditions.checkNotNull(kinesisVideoFrame);
        Preconditions.checkState(mStreamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);

        mLogger.debug("PutFrame index: {}, pts: {}, dts: {}, duration: {}, keyFrame: {}, track: {}",
                kinesisVideoFrame.getIndex(),
                kinesisVideoFrame.getPresentationTs(),
                kinesisVideoFrame.getDecodingTs(),
                kinesisVideoFrame.getDuration(),
                FrameFlags.isKeyFrame(kinesisVideoFrame.getFlags()),
                kinesisVideoFrame.getTrackId());

        // Print out metrics on every key-frame
        if (FrameFlags.isKeyFrame(kinesisVideoFrame.getFlags())) {
            final KinesisVideoMetrics kinesisVideoMetrics = mKinesisVideoProducerJni.getMetrics();
            final KinesisVideoStreamMetrics streamMetrics = getMetrics();
            mLogger.debug("Kinesis Video client and stream metrics"
                            + "\n\t>> Overall storage size: {}"
                            + "\n\t>> Available storage size: {}"
                            + "\n\t>> Allocated storage size: {}"
                            + "\n\t>> Total view allocation size: {}"
                            + "\n\t>> Total streams frame rate: {}"
                            + "\n\t>> Total streams transfer rate: {}"
                            + "\n\t>> Current view duration: {}"
                            + "\n\t>> Overall view duration: {}"
                            + "\n\t>> Current view size: {}"
                            + "\n\t>> Overall view size: {}"
                            + "\n\t>> Current frame rate: {}"
                            + "\n\t>> Current transfer rate: {}",
                    kinesisVideoMetrics.getContentStoreSize(),
                    kinesisVideoMetrics.getContentStoreAvailableSize(),
                    kinesisVideoMetrics.getContentStoreAllocatedSize(),
                    kinesisVideoMetrics.getTotalContentViewSize(),
                    kinesisVideoMetrics.getTotalFrameRate(),
                    kinesisVideoMetrics.getTotalTransferRate(),
                    streamMetrics.getCurrentViewDurationInTimeUnits(),
                    streamMetrics.getOverallViewDurationInTimeUnits(),
                    streamMetrics.getCurrentViewSize(),
                    streamMetrics.getOverallViewSize(),
                    streamMetrics.getCurrentFrameRate(),
                    streamMetrics.getCurrentTransferRate());
        }

        mKinesisVideoProducerJni.putFrame(mStreamHandle, kinesisVideoFrame);
    }

    @Override
    public void putFragmentMetadata(@Nonnull final String metadataName, @Nonnull final String metadataValue, boolean persistent)
            throws ProducerException {
        Preconditions.checkNotNull(metadataName);
        Preconditions.checkNotNull(metadataValue);
        Preconditions.checkState(mStreamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);

        mKinesisVideoProducerJni.putFragmentMetadata(mStreamHandle, metadataName, metadataValue, persistent);
    }


    @Override
    public void fragmentAck(final long uploadHandle, final @Nonnull KinesisVideoFragmentAck kinesisVideoFragmentAck) throws ProducerException {
        Preconditions.checkNotNull(kinesisVideoFragmentAck);
        Preconditions.checkState(mStreamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);

        mKinesisVideoProducerJni.fragmentAck(mStreamHandle, uploadHandle, kinesisVideoFragmentAck);
    }

    @Override
    public void parseFragmentAck(final long uploadHandle, final @Nonnull String kinesisVideoFragmentAck) throws ProducerException {
        Preconditions.checkNotNull(kinesisVideoFragmentAck);
        Preconditions.checkState(mStreamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);

        mKinesisVideoProducerJni.parseFragmentAck(mStreamHandle, uploadHandle, kinesisVideoFragmentAck);
    }

    @Override
    public void streamFormatChanged(@Nullable final byte[] codecPrivateData, int trackId)
            throws ProducerException {

        Preconditions.checkState(mStreamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);
        mKinesisVideoProducerJni.streamFormatChanged(mStreamHandle, codecPrivateData, trackId);
    }

    @Override
    public void streamTerminated(final long uploadHandle, final int statusCode) throws ProducerException {
        Preconditions.checkState(mStreamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);
        mKinesisVideoProducerJni.streamTerminated(mStreamHandle, uploadHandle, statusCode);
    }

    @Override
    public void stopStream() throws ProducerException {
        // Stop the underlying native stream
        mKinesisVideoProducerJni.stopStream(mStreamHandle);
    }

    @Override
    public void stopStreamSync() throws ProducerException {
        // Kick off stream stop
        stopStream();

        // Await for the data to finish.
        try {
            awaitStopped();
        } catch (final ProducerException e) {
            Exception storedException = null;
            for (NativeDataInputStream inputStream : mInputStreamMap.values()) {
                try {
                    inputStream.close();
                } catch (final IOException e1) {
                    storedException = e1;
                }
            }
            mLogger.error("Stopping stream threw an exception. Force stopping the input stream.", e);
            if (storedException != null) {
                throw new ProducerException(storedException);
            }
        } finally {
            for (final InputStream stream : mInputStreamMap.values()) {
                try {
                    stream.close();
                } catch (final IOException e) {
                    mLogger.error(e);
                }
            }
        }
    }

    @Nonnull
    @Override
    public KinesisVideoStreamMetrics getMetrics() throws ProducerException {
        Preconditions.checkState(mStreamHandle != NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE);
        mKinesisVideoProducerJni.getStreamMetrics(mStreamHandle, mStreamMetrics);
        return mStreamMetrics;
    }

    @Override
    public String getStreamName() {
        return mStreamInfo.getName();
    }

    @Override
    public long getStreamHandle() {
        return mStreamHandle;
    }

    @Override
    public void streamFreed() throws ProducerException {
        streamClosed(ReadResult.INVALID_UPLOAD_HANDLE_VALUE);
        mStreamHandle = NativeKinesisVideoProducerJni.INVALID_STREAM_HANDLE_VALUE;
    }

    @Override
    public void streamUnderflowReport() throws ProducerException
    {
        if (mStreamCallbacks != null) {
            mStreamCallbacks.streamUnderflowReport();
        }
    }

    @Override
    public void streamLatencyPressure(final long duration) throws ProducerException
    {
        if (mStreamCallbacks != null) {
            mStreamCallbacks.streamLatencyPressure(duration);
        }
    }

    @Override
    public void streamConnectionStale(final long lastAckDuration) throws ProducerException
    {
        if (mStreamCallbacks != null) {
            mStreamCallbacks.streamConnectionStale(lastAckDuration);
        }
    }

    @Override
    public void fragmentAckReceived(final long uploadHandle,
                                    @Nonnull final KinesisVideoFragmentAck fragmentAck) throws ProducerException
    {
        if (mStreamCallbacks != null) {
            mStreamCallbacks.fragmentAckReceived(uploadHandle, fragmentAck);
        }
    }

    @Override
    public void droppedFrameReport(final long frameTimecode) throws ProducerException
    {
        if (mStreamCallbacks != null) {
            mStreamCallbacks.droppedFrameReport(frameTimecode);
        }
    }

    @Override
    public void droppedFragmentReport(final long fragmentTimecode) throws ProducerException
    {
        if (mStreamCallbacks != null) {
            mStreamCallbacks.droppedFragmentReport(fragmentTimecode);
        }
    }

    @Override
    public void streamErrorReport(final long uploadHandle, final long fragmentTimecode, final long statusCode) throws ProducerException
    {
        if (mStreamCallbacks != null) {
            mStreamCallbacks.streamErrorReport(uploadHandle, fragmentTimecode, statusCode);
        }
    }

    @Override
    public void streamDataAvailable(final long uploadHandle, final long duration, final long availableSize) throws ProducerException
    {
        // Unblock the reader thread
        final NativeDataInputStream inputStreamToNotify = mInputStreamMap.get(uploadHandle);
        if (inputStreamToNotify != null) {
            inputStreamToNotify.notifyReaderThread(duration, availableSize);
        } else {
            mLogger.warn("Data available notification for non-existing uploadHandle {}", uploadHandle);
        }

        if (mStreamCallbacks != null) {
            mStreamCallbacks.streamDataAvailable(uploadHandle, duration, availableSize);
        }
    }

    @Override
    public void streamReady() throws ProducerException
    {
        mLogger.debug("Stream {} is ready", mStreamInfo.getName());
        // Release the ready latch
        mReadyLatch.countDown();

        if (mStreamCallbacks != null) {
            mStreamCallbacks.streamReady();
        }
    }

    @Override
    public void streamClosed(final long uploadHandle) throws ProducerException
    {
        mLogger.debug("Stream {} is closed", mStreamInfo.getName());

        if (uploadHandle == ReadResult.INVALID_UPLOAD_HANDLE_VALUE) {
            for (final Map.Entry<Long, NativeDataInputStream> stream : mInputStreamMap.entrySet()) {
                try {
                    stream.getValue().close();
                    if (mStreamCallbacks != null) {
                        mStreamCallbacks.streamClosed(stream.getKey());
                    }
                } catch (final IOException e) {
                    mLogger.error("stream close failed with exception ", e);
                }
            }

            // Release the stopped latch
            mStoppedLatch.countDown();
        } else {
            try {
                mInputStreamMap.get(uploadHandle).close();
            } catch (final IOException e) {
                mLogger.error("stream close failed with exception ", e);
            }

            // Release the stopped latch
            mStoppedLatch.countDown();

            if (mStreamCallbacks != null) {
                mStreamCallbacks.streamClosed(uploadHandle);
            }
        }
    }

    @Override
    public void bufferDurationOverflowPressure(final long remainDuration) throws ProducerException {
        if (mStreamCallbacks != null) {
            mStreamCallbacks.bufferDurationOverflowPressure(remainDuration);
        }
    }

    /**
     * Create a new connection to continue sending data in current buffer
     * @throws ProducerException
     */
    @Override
    public void resetConnection() throws ProducerException {
        mLogger.debug("Current connection of stream {} is being reset", mStreamInfo.getName());

        streamTerminated(ReadResult.INVALID_UPLOAD_HANDLE_VALUE, SERVICE_CALL_RESULT_OK);
    }

    public void awaitReady() throws ProducerException
    {
        // Block until client is ready or it times out.
        try {
            if (!mReadyLatch.await(READY_TIMEOUT_IN_MILLISECONDS, TimeUnit.MILLISECONDS)) {
                throw new ProducerException("KinesisVideo producer stream creation time out", ProducerException.STATUS_OPERATION_TIMED_OUT);
            }
        } catch (final InterruptedException e) {
            throw new ProducerException(e);
        }
    }

    public void awaitStopped() throws ProducerException
    {
        // Block until client is stopped or it times out.
        try {
            // Default time unit precision supplied should be in milliseconds
            long timeout = mDeviceInfo.getClientInfo().getStopStreamTimeout() / Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
            if (timeout == 0) {
                timeout = STOPPED_TIMEOUT_IN_MILLISECONDS;
            }
            if (!mStoppedLatch.await(timeout, TimeUnit.MILLISECONDS)) {
                throw new ProducerException("KinesisVideo producer stream stopping time out", ProducerException.STATUS_OPERATION_TIMED_OUT);
            }

        } catch (final InterruptedException e) {
            throw new ProducerException(e);
        }
    }
}
