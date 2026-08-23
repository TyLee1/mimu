package dev.fishvoicesetup.shell;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;

import java.io.File;

final class AudioPlayer {
    private final Handler main;
    private MediaPlayer player;

    AudioPlayer(Handler main) { this.main = main; }

    void play(File file) {
        if (file == null || !file.exists()) return;
        stop();
        try {
            player = create(file);
            player.setOnCompletionListener(mp -> stop());
            player.start();
        } catch (Exception ignored) { stop(); }
    }

    void playSequence(File[] files) { playAt(files, 0); }

    private void playAt(File[] files, int index) {
        if (files == null || index >= files.length) return;
        File file = files[index];
        if (file == null || !file.exists()) { playAt(files, index + 1); return; }
        stop();
        try {
            player = create(file);
            player.setOnCompletionListener(mp -> {
                stop();
                main.postDelayed(() -> playAt(files, index + 1), 350);
            });
            player.start();
        } catch (Exception ignored) {
            stop();
            playAt(files, index + 1);
        }
    }

    private MediaPlayer create(File file) throws Exception {
        MediaPlayer p = new MediaPlayer();
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        p.setDataSource(file.getAbsolutePath());
        p.prepare();
        return p;
    }

    void stop() {
        if (player == null) return;
        try { player.stop(); } catch (Exception ignored) {}
        try { player.release(); } catch (Exception ignored) {}
        player = null;
    }
}
