package com.example.app;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.Toast;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.View;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

public class TvActivity extends Activity {
    boolean DoublePressToExit = false;
    private boolean isMuted = false; // Initial mute state
    private int previousVolume = -1; // To store the previous volume
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    Toast toast;
    // creating a variable for
    // button and media player

    ImageView playbtn;
    ImageView btnMute;

    MediaPlayer mediaPlayer;

    Handler handler;

    private WebView mWebView;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        mWebView.setWebViewClient(new MyWebViewClient());

        // REMOTE RESOURCE
        mWebView.loadUrl("https://purplescorpion1.github.io/");

        // LOCAL RESOURCE
        // mWebView.loadUrl("file:///android_asset/index.html");

        playbtn = findViewById(R.id.btnplay);
        btnMute = findViewById(R.id.btnmute);

        playbtn.setOnClickListener(btnClickListen);

        mediaPlayer = new MediaPlayer();

        handler = new Handler();

        // Find your ImageView (btnmute) by its ID
        ImageView btnMute = findViewById(R.id.btnmute);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Create a MediaSession
        mediaSession = new MediaSessionCompat(this, "MuteAudio");

        // Set up the media session's playback state
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .build();
        mediaSession.setPlaybackState(playbackState);

        // Set a click listener for the Mute button
        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mediaPlayer.isPlaying()) {
                    if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                        // Unmute audio
                        audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false);
                        mediaSession.setActive(true);
                        PlaybackStateCompat updatedPlaybackState = new PlaybackStateCompat.Builder()
                                .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                                .build();
                        mediaSession.setPlaybackState(updatedPlaybackState);
                        btnMute.setImageResource(R.drawable.mute_highlighted);
                        Toast.makeText(TvActivity.this, "Audio has been unmuted", Toast.LENGTH_SHORT).show();
                    } else {
                        // Mute audio
                        audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true);
                        mediaSession.setActive(false);
                        PlaybackStateCompat updatedPlaybackState = new PlaybackStateCompat.Builder()
                                .setState(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                                .build();
                        mediaSession.setPlaybackState(updatedPlaybackState);
                        btnMute.setImageResource(R.drawable.unmute_highlighted);
                        Toast.makeText(TvActivity.this, "Audio has been muted", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mediaSession.release();
    }

    private View.OnClickListener btnClickListen = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
                playbtn.setImageResource(R.drawable.play_highlighted);
                Toast.makeText(TvActivity.this, "Audio has been paused", Toast.LENGTH_SHORT).show();
            } else {
                playbtn.setImageResource(R.drawable.pause_highlighted);
                PlaySong();
                Toast.makeText(TvActivity.this, "Audio is starting. Please wait...", Toast.LENGTH_SHORT).show();
            }
        }
    };


    public void PlaySong(){
        Uri uri = Uri.parse("https://c7.radioboss.fm:18205/stream");
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.reset();

        try {
            mediaPlayer.setDataSource(TvActivity.this, uri);
        }catch (Exception e){e.printStackTrace();}

        mediaPlayer.prepareAsync();
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mediaPlayer.start();
            }
        });

        mediaPlayer.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
            @Override
            public void onBufferingUpdate(MediaPlayer mp, int percent) {

            }
        });

    }

    @Override
    public void onBackPressed() {
        if (DoublePressToExit){
            finishAffinity();
            toast.cancel();
        }else {
            DoublePressToExit=true;
            Toast.makeText(this, "Press Again To Exit", Toast.LENGTH_SHORT).show();
            Handler handler=new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    DoublePressToExit=false;
                }
            },2000);
        }
    }

}