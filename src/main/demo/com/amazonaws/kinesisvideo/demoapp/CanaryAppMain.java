package com.amazonaws.kinesisvideo.demoapp;

import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.demoapp.contants.DemoTrackInfos;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.demoapp.auth.AuthHelper;
import com.amazonaws.kinesisvideo.java.client.KinesisVideoJavaClientFactory;
import com.amazonaws.kinesisvideo.java.mediasource.file.*;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClientBuilder;
import com.amazonaws.services.cloudwatch.model.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.ABSOLUTE_TIMECODES;

/**
 * Demo Java Producer.
 */
public final class CanaryAppMain {
    // Use a different stream name when testing audio/video sample
    private static final String STREAM_NAME = System.getProperty("kvs-stream");
    private static final int FPS_25 = 25;
    private static final int RETENTION_ONE_HOUR = 1;
    private static final String IMAGE_DIR = "src/main/resources/data/h264/";
    private static final String FRAME_DIR = "src/main/resources/data/audio-video-frames";
    // CHECKSTYLE:SUPPRESS:LineLength
    // Need to get key frame configured properly so the output can be decoded. h264 files can be decoded using gstreamer plugin
    // gst-launch-1.0 rtspsrc location="YourRtspUri" short-header=TRUE protocols=tcp ! rtph264depay ! decodebin ! videorate ! videoscale ! vtenc_h264_hw allow-frame-reordering=FALSE max-keyframe-interval=25 bitrate=1024 realtime=TRUE ! video/x-h264,stream-format=avc,alignment=au,profile=baseline,width=640,height=480,framerate=1/25 ! multifilesink location=./frame-%03d.h264 index=1
    private static final String IMAGE_FILENAME_FORMAT = "frame-%03d.h264";
    private static final int START_FILE_INDEX = 1;
    private static final int END_FILE_INDEX = 375;

    private final Log log = LogFactory.getLog(CanaryAppMain.class);

    private CanaryAppMain() {
        throw new UnsupportedOperationException();
    }

    public static void sendMetrics(java.util.List<MetricDatum> datumList, AmazonCloudWatchAsync cloudWatchClient) {
        PutMetricDataRequest request = new PutMetricDataRequest()
                .withNamespace("KVSJavaCW")
                .withMetricData(datumList);
        System.out.println("###############################");
        cloudWatchClient.putMetricDataAsync(request);
        System.out.println("DONE!!!");
    }

    public static void main(final String[] args) {

        Instant start = Instant.now();
        try {
            // create Kinesis Video high level client
            final KinesisVideoClient kinesisVideoClient = KinesisVideoJavaClientFactory
                    .createKinesisVideoClient(
                            Regions.US_WEST_2,
                            AuthHelper.getSystemPropertiesCredentialsProvider());

            final AmazonCloudWatchAsync cloudWatchClient = AmazonCloudWatchAsyncClientBuilder.standard()
                    .withRegion("us-west-2")
                    .withCredentials(new SystemPropertiesCredentialsProvider())
                    .build();

            Dimension dimensionPerStream = new Dimension()
                    .withName("ProducerSDKCanaryStreamName")
                    .withValue("Test123");

            List<MetricDatum> datumList = new ArrayList<>();
            MetricDatum datum = new MetricDatum()
                    .withMetricName("StartTimeLatency")
                    .withUnit(StandardUnit.Milliseconds)
                    .withValue(123.3)
                    .withDimensions(dimensionPerStream);
            datumList.add(datum);
            sendMetrics(datumList, cloudWatchClient);




            // create a media source. this class produces the data and pushes it into
            // Kinesis Video Producer lower level components
            final CanaryImageFileMediaSource mediaSource = createCanaryImageFileMediaSource(start, cloudWatchClient);

            // Audio/Video sample is available for playback on HLS (Http Live Streaming)
            //final MediaSource mediaSource = createFileMediaSource();

            // register media source with Kinesis Video Client
            kinesisVideoClient.registerMediaSource(mediaSource);

            // start streaming

            mediaSource.start();
        } catch (final KinesisVideoException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a MediaSource based on local sample H.264 frames.
     *
     * @return a MediaSource backed by local H264 frame files
     */
    private static CanaryImageFileMediaSource createCanaryImageFileMediaSource(Instant startTimestamp,
                                                                               AmazonCloudWatchAsync cloudWatchClient) {
        final CanaryImageFileMediaSourceConfiguration configuration =
                new CanaryImageFileMediaSourceConfiguration.Builder()
                        .fps(FPS_25)
                        .dir(IMAGE_DIR)
                        .filenameFormat(IMAGE_FILENAME_FORMAT)
                        .startFileIndex(START_FILE_INDEX)
                        .endFileIndex(END_FILE_INDEX)
                        .startTimestamp(startTimestamp)
                        .cloudWatchClient(cloudWatchClient)
                        .streamDurationInSeconds(5)
                        //.contentType("video/hevc") // for h265
                        .build();
        final CanaryImageFileMediaSource mediaSource = new CanaryImageFileMediaSource(STREAM_NAME);
        mediaSource.configure(configuration);

        return mediaSource;
    }

    private static void setUpCloudWatchInMediaSource(MediaSource mediaSource) {

    }

    /**
     * Create a MediaSource based on local sample H.264 frames and AAC frames.
     *
     * @return a MediaSource backed by local H264 and AAC frame files
     */
    private static MediaSource createFileMediaSource() {
        final AudioVideoFileMediaSourceConfiguration configuration =
                new AudioVideoFileMediaSourceConfiguration.AudioVideoBuilder()
                        .withDir(FRAME_DIR)
                        .withRetentionPeriodInHours(RETENTION_ONE_HOUR)
                        .withAbsoluteTimecode(ABSOLUTE_TIMECODES)
                        .withTrackInfoList(DemoTrackInfos.createTrackInfoList())
                        .build();
        final AudioVideoFileMediaSource mediaSource = new AudioVideoFileMediaSource(STREAM_NAME);
        mediaSource.configure(configuration);

        return mediaSource;
    }
}
