package com.newslive.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕录制（DRM 流的唯一可录路径）。
 *
 * 视频：MediaProjection → VirtualDisplay → MediaCodec(H.264) → MediaMuxer(MP4)
 * 音频：AudioPlaybackCapture（Android 10+）抓本应用正在播放的 PCM → AAC → 同一 MP4
 *
 * 为什么 DRM 流只能这么录：分片是密文，明文只存在于 CDM→解码器 的受保护通路，
 * App 取不到；能拿到的是渲染后的像素和混音后的 PCM——那就把两者采回来重新封装。
 */
public class ScreenRecorder {

    public interface Listener {
        void onStarted(ScreenRecorder r);
        void onStopped(ScreenRecorder r, File file, long bytes, boolean hadAudio);
        void onError(ScreenRecorder r, String message);
    }

    private static final String MIME_VIDEO = "video/avc";
    private static final String MIME_AUDIO = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final long AUDIO_WAIT_MS = 3000;

    private final MediaProjection projection;
    private final File outFile;
    private final int width, height, dpi;
    private final boolean wantAudio;
    private final Listener listener;

    private MediaCodec vEnc, aEnc;
    private MediaMuxer muxer;
    private android.hardware.display.VirtualDisplay display;
    private AudioRecord audioRecord;

    private Thread vThread, aThread;
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile boolean muxerStarted;
    private int vTrack = -1, aTrack = -1;
    private MediaFormat videoFormat, audioFormat;
    private long t0Us, startWallMs;
    private long audioStartOffsetUs;
    private long audioSamples;
    private volatile boolean hadAudio;
    private volatile long bytes;

    private final Object muxLock = new Object();
    private final List<Pending> pending = new ArrayList<>();

    private static class Pending {
        int kind;              // 0=video 1=audio
        long ptsUs;
        int flags;
        ByteBuffer data;
    }

    public ScreenRecorder(MediaProjection projection, File outFile,
                          int width, int height, int dpi, boolean wantAudio, Listener l) {
        this.projection = projection;
        this.outFile = outFile;
        this.width = width & ~1;      // H.264 要求偶数
        this.height = height & ~1;
        this.dpi = dpi;
        this.wantAudio = wantAudio && Build.VERSION.SDK_INT >= 29;
        this.listener = l;
    }

    public File getFile() { return outFile; }

    public boolean isRecording() { return running; }

    public long getBytes() { return bytes; }

    public void start() {
        try {
            if (outFile.exists()) outFile.delete();
            startWallMs = System.currentTimeMillis();
            t0Us = System.nanoTime() / 1000L;

            MediaFormat vf = MediaFormat.createVideoFormat(MIME_VIDEO, width, height);
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // 码率压在 2~2.5Mbps：MP4 单文件在 4GB 以内才稳，按 1080p≈2Mbps 可录约 4 小时
            vf.setInteger(MediaFormat.KEY_BIT_RATE,
                    Math.min(2_500_000, Math.max(1_500_000, width * height)));
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            vEnc = MediaCodec.createEncoderByType(MIME_VIDEO);
            vEnc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface input = vEnc.createInputSurface();
            vEnc.start();

            if (wantAudio) {
                try {
                    startAudio();
                } catch (Exception e) {
                    LogUtil.w("ScreenRecorder", "audio capture unavailable: " + e);
                }
            }

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            running = true;

            display = projection.createVirtualDisplay(
                    "newslive-rec", width, height, dpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    input, null, null);

            vThread = new Thread(new Runnable() {
                @Override public void run() { drainVideo(); }
            }, "rec-video");
            vThread.start();

            if (wantAudio && aEnc != null) {
                aThread = new Thread(new Runnable() {
                    @Override public void run() { encodeAudio(); }
                }, "rec-audio");
                aThread.start();
            }

            if (listener != null) listener.onStarted(this);
            LogUtil.i("ScreenRecorder", "start " + width + "x" + height + " audio=" + (aEnc != null));
        } catch (Exception e) {
            LogUtil.e("ScreenRecorder", "start failed", e);
            releaseAll();
            if (listener != null) listener.onError(this, String.valueOf(e.getMessage()));
        }
    }

    private void startAudio() throws Exception {
        AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNELS == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                CHANNELS == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(minBuf * 2, SAMPLE_RATE * 2 * 2))
                .setAudioPlaybackCaptureConfig(cfg)
                .build();

        MediaFormat af = MediaFormat.createAudioFormat(MIME_AUDIO, SAMPLE_RATE, CHANNELS);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        aEnc = MediaCodec.createEncoderByType(MIME_AUDIO);
        aEnc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aEnc.start();
        audioRecord.startRecording();
        audioStartOffsetUs = System.nanoTime() / 1000L - t0Us;
    }

    // ---------------------------------------------------------------- 视频

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int idx = vEnc.dequeueOutputBuffer(info, 10000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (stopping) continue;   // 等 EOS 标志，别提前退出丢尾帧
                    continue;
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoFormat = vEnc.getOutputFormat();
                    tryStartMuxer();
                    continue;
                }
                if (idx < 0) continue;

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0) {
                    ByteBuffer buf = vEnc.getOutputBuffer(idx);
                    if (buf != null) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        long pts = Math.max(0, info.presentationTimeUs - t0Us);
                        writeSample(0, buf, pts, info.flags);
                    }
                }
                vEnc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Exception e) {
            if (running) {
                LogUtil.e("ScreenRecorder", "video drain failed", e);
                if (listener != null) listener.onError(this, String.valueOf(e.getMessage()));
            }
        }
    }

    // ---------------------------------------------------------------- 音频

    private void encodeAudio() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcm = new byte[8192];
        try {
            while (!stopping) {
                int n = audioRecord.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                int inIdx = aEnc.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer ib = aEnc.getInputBuffer(inIdx);
                    if (ib != null) {
                        ib.clear();
                        ib.put(pcm, 0, n);
                        long pts = audioStartOffsetUs + audioSamples * 1_000_000L / SAMPLE_RATE;
                        aEnc.queueInputBuffer(inIdx, 0, n, pts, 0);
                        audioSamples += n / (2 * CHANNELS);
                    }
                }
                drainAudio(info);
            }
            int inIdx = aEnc.dequeueInputBuffer(20000);
            if (inIdx >= 0) {
                long pts = audioStartOffsetUs + audioSamples * 1_000_000L / SAMPLE_RATE;
                aEnc.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            for (int i = 0; i < 60; i++) {
                drainAudio(info);
                Thread.sleep(8);
            }
        } catch (Exception e) {
            LogUtil.w("ScreenRecorder", "audio thread: " + e);
        }
    }

    private void drainAudio(MediaCodec.BufferInfo info) {
        try {
            while (true) {
                int idx = aEnc.dequeueOutputBuffer(info, 0);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return;
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioFormat = aEnc.getOutputFormat();
                    tryStartMuxer();
                    continue;
                }
                if (idx < 0) continue;

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    ByteBuffer buf = aEnc.getOutputBuffer(idx);
                    if (buf != null) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        writeSample(1, buf, Math.max(0, info.presentationTimeUs), info.flags);
                    }
                }
                aEnc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        } catch (Exception ignore) { }
    }

    // ---------------------------------------------------------------- 封装

    /** 视频/音频格式都拿到才开始 muxer（MediaMuxer 的轨道必须在 start 之前加完） */
    private void tryStartMuxer() {
        synchronized (muxLock) {
            if (muxerStarted || muxer == null) return;
            if (videoFormat == null) return;
            if (wantAudio && audioFormat == null) {
                // 3 秒还没等到音频格式（可能本机 / 该页面没有可采音频）→ 先按纯视频起封装
                if (System.currentTimeMillis() - startWallMs < AUDIO_WAIT_MS) return;
            }
            try {
                vTrack = muxer.addTrack(videoFormat);
                if (audioFormat != null) aTrack = muxer.addTrack(audioFormat);
                muxer.start();
                muxerStarted = true;
                for (Pending p : pending) writeLocked(p);
                pending.clear();
            } catch (Exception e) {
                LogUtil.e("ScreenRecorder", "muxer start failed", e);
            }
        }
    }

    private void writeSample(int kind, ByteBuffer buf, long ptsUs, int flags) {
        synchronized (muxLock) {
            if (!muxerStarted) {
                if (pending.size() > 200) return;   // 起播瞬间最多缓 200 帧
                Pending p = new Pending();
                p.kind = kind;
                p.ptsUs = ptsUs;
                p.flags = flags;
                p.data = ByteBuffer.allocate(buf.remaining());
                p.data.put(buf);
                p.data.flip();
                pending.add(p);
                return;
            }
            int track = kind == 0 ? vTrack : aTrack;
            if (track < 0) return;
            try {
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                bi.set(0, buf.remaining(), ptsUs, flags);
                muxer.writeSampleData(track, buf, bi);
                bytes += buf.remaining();
                if (kind == 1) hadAudio = true;
            } catch (Exception e) {
                LogUtil.w("ScreenRecorder", "write sample: " + e);
            }
        }
    }

    private void writeLocked(Pending p) {
        int track = p.kind == 0 ? vTrack : aTrack;
        if (track < 0) return;
        try {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            bi.set(0, p.data.remaining(), p.ptsUs, p.flags);
            muxer.writeSampleData(track, p.data, bi);
            bytes += p.data.remaining();
            if (p.kind == 1) hadAudio = true;
        } catch (Exception ignore) { }
    }

    // ---------------------------------------------------------------- 停止

    public void stop() {
        if (!running) return;
        stopping = true;
        try {
            if (aThread != null) aThread.join(1200);
        } catch (Exception ignore) { }
        try {
            if (vEnc != null) vEnc.signalEndOfInputStream();
        } catch (Exception ignore) { }
        try {
            if (vThread != null) vThread.join(2500);
        } catch (Exception ignore) { }

        running = false;
        long size = outFile.exists() ? outFile.length() : 0;
        boolean audio = hadAudio;
        releaseAll();
        if (listener != null) listener.onStopped(this, outFile, size, audio);
        LogUtil.i("ScreenRecorder", "stop size=" + size + " audio=" + audio);
    }

    private void releaseAll() {
        try { if (display != null) display.release(); } catch (Exception ignore) { }
        display = null;
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception ignore) { }
        audioRecord = null;
        try { if (vEnc != null) { vEnc.stop(); vEnc.release(); } } catch (Exception ignore) { }
        vEnc = null;
        try { if (aEnc != null) { aEnc.stop(); aEnc.release(); } } catch (Exception ignore) { }
        aEnc = null;
        synchronized (muxLock) {
            try {
                if (muxer != null) {
                    if (muxerStarted) muxer.stop();
                    muxer.release();
                }
            } catch (Exception ignore) { }
            muxer = null;
            muxerStarted = false;
        }
        try { projection.stop(); } catch (Exception ignore) { }
    }
}
