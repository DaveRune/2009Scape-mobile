package AudioFix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import plugin.Plugin;
import plugin.annotations.PluginMeta;
import rt4.AudioChannel;
import rt4.client;

/// Makes sound effects, ambient sounds and music play properly through the client's two OpenAL channels.
/// A channel guesses how much audio is still waiting rather than asking OpenAL, and each opens its own
/// context, so both believe they own source number one. This hands them a source each and sets what they
/// write towards from what OpenAL really reports, leaving the client's own audio thread to do the writing.
@PluginMeta(author = "Dave", description = "Repairs OpenAL audio so sound effects play and music does not run away", version = 3.2)
public class plugin extends Plugin {

    private static final String CHANNEL_CLASS = "rt4.OpenALAudioChannel";
    private static final int GIVE_UP_AFTER_FRAMES = 3000;
    private static final int BUFFERS_TO_KEEP_AHEAD = 8;

    private boolean ready = false;
    private boolean failed = false;
    private int framesWaited = 0;

    private Method getBufferSize;
    private Field bufferSizeAdjustment;
    private Field sourceField;
    private Field sampleCounterField;

    @Override
    public void Draw(long elapsed) {
        if (failed) return;

        if (!ready) {
            if (++framesWaited > GIVE_UP_AFTER_FRAMES) {
                failed = true;
                System.out.println("AudioFix: gave up waiting for both audio channels.");
                return;
            }
            if (!isOpenAL(client.musicChannel) || !isOpenAL(client.soundChannel)) return;
            if (!prepare()) return;
            ready = true;
        }

        separateSources(client.musicChannel, client.soundChannel);
        clearSampleCounter(client.musicChannel);
        clearSampleCounter(client.soundChannel);
        steer(client.musicChannel);
        steer(client.soundChannel);
    }

    private boolean isOpenAL(AudioChannel channel) {
        return channel != null && CHANNEL_CLASS.equals(channel.getClass().getName());
    }

    private boolean prepare() {
        try {
            getBufferSize = AudioChannel.class.getDeclaredMethod("getBufferSize");
            getBufferSize.setAccessible(true);
            bufferSizeAdjustment = AudioChannel.class.getDeclaredField("bufferSizeAdjustment");
            bufferSizeAdjustment.setAccessible(true);
            sourceField = client.soundChannel.getClass().getDeclaredField("source");
            sourceField.setAccessible(true);
            sampleCounterField = client.soundChannel.getClass().getDeclaredField("sampleCounter");
            sampleCounterField.setAccessible(true);
            System.out.println("AudioFix: ready.");
            return true;
        } catch (Exception e) {
            failed = true;
            System.out.println("AudioFix: could not reach the audio fields, '" + e + "'.");
            return false;
        }
    }

    /// Every fresh OpenAL context issues source number one, and the client can replace either channel while the game runs.
    private void separateSources(AudioChannel music, AudioChannel sound) {
        try {
            Field contextField = music.getClass().getDeclaredField("audioContext");
            contextField.setAccessible(true);

            long musicContext = contextField.getLong(music);
            if (musicContext == contextField.getLong(sound)) {
                if (ALC10.alcGetCurrentContext() != musicContext) {
                    ALC10.alcMakeContextCurrent(musicContext);
                    System.out.println("AudioFix: current context had drifted, put back to the shared one.");
                }
                return;
            }

            ALC10.alcMakeContextCurrent(musicContext);
            int newSource = AL10.alGenSources();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                failed = true;
                System.out.println("AudioFix: could not create a second source.");
                return;
            }

            sourceField.setInt(sound, newSource);
            contextField.setLong(sound, musicContext);
            System.out.println("AudioFix: sound channel given source '" + newSource + "' in the music context.");
        } catch (Exception e) {
            failed = true;
            System.out.println("AudioFix: could not separate the sources, '" + e + "'.");
        }
    }

    /// Playing a jingle stops the client resetting this counter, and one that only rises makes the channel report itself full.
    private void clearSampleCounter(AudioChannel channel) {
        try {
            ((AtomicInteger) sampleCounterField.get(channel)).set(0);
        } catch (Exception e) {
            failed = true;
            System.out.println("AudioFix: could not clear the sample counter, '" + e + "'.");
        }
    }

    /// The amount a channel reports as still waiting to play is invented, so what it writes towards is set from what OpenAL has.
    private void steer(AudioChannel channel) {
        try {
            int ahead = buffersAhead(sourceField.getInt(channel));
            int reportedPending = (Integer) getBufferSize.invoke(channel);

            int target = ahead >= BUFFERS_TO_KEEP_AHEAD ? 0 : reportedPending + 256;
            bufferSizeAdjustment.setInt(channel, target - channel.channelSampleRate);
        } catch (Exception e) {
            failed = true;
            System.out.println("AudioFix: steering failed, '" + e + "'.");
        }
    }

    private int buffersAhead(int source) {
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        return queued - processed;
    }
}
