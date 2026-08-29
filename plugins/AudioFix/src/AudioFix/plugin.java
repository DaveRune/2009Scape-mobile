package AudioFix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import plugin.Plugin;
import plugin.annotations.PluginMeta;
import rt4.AudioChannel;
import rt4.client;

/// Makes sound effects and ambient sounds audible and stops music burying itself in unplayed audio.
/// The client's OpenAL channel invents how much audio is still waiting instead of asking OpenAL, so the
/// sound channel believes it is permanently full and never writes, while music writes on a fixed timer and
/// drifts thousands of buffers ahead until it stops. This corrects the target the channel writes towards,
/// using what OpenAL actually reports, and leaves the client's own audio thread to do the writing.
@PluginMeta(author = "Dave", description = "Repairs OpenAL audio so sound effects play and music does not run away", version = 3.0)
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
            separateSources(client.musicChannel, client.soundChannel);
            ready = true;
        }

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
            System.out.println("AudioFix: ready.");
            return true;
        } catch (Exception e) {
            failed = true;
            System.out.println("AudioFix: could not reach the audio fields, " + e);
            return false;
        }
    }

    /// Each channel builds its own OpenAL context, and every fresh context issues source number 1,
    /// so without this both channels write into the same source.
    private void separateSources(AudioChannel music, AudioChannel sound) {
        try {
            Field contextField = music.getClass().getDeclaredField("audioContext");
            contextField.setAccessible(true);

            long musicContext = contextField.getLong(music);
            if (musicContext == contextField.getLong(sound)) return;

            ALC10.alcMakeContextCurrent(musicContext);
            int newSource = AL10.alGenSources();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                System.out.println("AudioFix: could not create a second source.");
                return;
            }

            sourceField.setInt(sound, newSource);
            contextField.setLong(sound, musicContext);
            System.out.println("AudioFix: sound channel given source '" + newSource + "' in the music context.");
        } catch (Exception e) {
            System.out.println("AudioFix: could not separate the sources, " + e);
        }
    }

    /// The channel writes while its target exceeds the amount it thinks is pending. That pending figure is
    /// invented, so the target is set here from what OpenAL really has left, letting one chunk through at a
    /// time while the channel is short and none once it is far enough ahead.
    private void steer(AudioChannel channel) {
        try {
            int ahead = buffersAhead(sourceField.getInt(channel));
            int reportedPending = (Integer) getBufferSize.invoke(channel);

            int target = ahead >= BUFFERS_TO_KEEP_AHEAD ? 0 : reportedPending + 256;
            bufferSizeAdjustment.setInt(channel, target - channel.channelSampleRate);
        } catch (Exception e) {
            failed = true;
            System.out.println("AudioFix: steering failed, " + e);
        }
    }

    private int buffersAhead(int source) {
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        return queued - processed;
    }
}
