package com.example.app;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Build;
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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

public class TvActivity extends Activity {
    boolean DoublePressToExit = false;
    private boolean isMuted = false; // Initial mute state
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    Toast toast;
    // creating a variable for
    // button and media player

    ImageView playbtn;
    ImageView btnMute;

    private MediaPlaybackService mediaPlaybackService;
    private boolean isBound = false;

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
        mWebView.addJavascriptInterface(new WebAppInterface(this), "Android");

        // REMOTE RESOURCE
        mWebView.loadUrl("https://purplescorpion1.github.io/");

        // LOCAL RESOURCE
        // mWebView.loadUrl("file:///android_asset/index.html");

        playbtn = findViewById(R.id.btnplay);
        btnMute = findViewById(R.id.btnmute);

        playbtn.setOnClickListener(btnClickListen);

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
                if (isBound && mediaPlaybackService.isPlaying()) {
                    if (isMuted) {
                        mediaPlaybackService.unmute();
                        isMuted = false;
                        btnMute.setImageResource(R.drawable.mute_highlighted);
                        Toast.makeText(TvActivity.this, "Audio has been unmuted", Toast.LENGTH_SHORT).show();
                        mediaSession.setActive(true);
                    } else {
                        mediaPlaybackService.mute();
                        isMuted = true;
                        btnMute.setImageResource(R.drawable.unmute_highlighted);
                        Toast.makeText(TvActivity.this, "Audio has been muted", Toast.LENGTH_SHORT).show();
                        mediaSession.setActive(false);
                    }
                }
            }
        });

        Intent intent = new Intent(this, MediaPlaybackService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mediaSession.release();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MediaPlaybackService.LocalBinder binder = (MediaPlaybackService.LocalBinder) service;
            mediaPlaybackService = binder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    private View.OnClickListener btnClickListen = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (isBound) {
                if (mediaPlaybackService.isPlaying()) {
                    mediaPlaybackService.stop();
                    playbtn.setImageResource(R.drawable.play_highlighted);
                    Toast.makeText(TvActivity.this, "Audio has been paused", Toast.LENGTH_SHORT).show();
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
                } else {
                    mediaPlaybackService.play();
                    playbtn.setImageResource(R.drawable.pause_highlighted);
                    Toast.makeText(TvActivity.this, "Audio is starting. Please wait...", Toast.LENGTH_SHORT).show();
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    mediaSession.setActive(true);
                }
            }
        }
    };

    @Override
    public void onBackPressed() {
        if (DoublePressToExit){
            finish();
            toast.cancel();
        }else {
            DoublePressToExit=true;
            toast = Toast.makeText(this, "Press Again To Exit", Toast.LENGTH_SHORT);
            toast.show();
            Handler handler=new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    DoublePressToExit=false;
                }
            },2000);
        }
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @android.webkit.JavascriptInterface
        public void showToast(String toast) {
            Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
        }

        @android.webkit.JavascriptInterface
        public void updateTrackInfo(String title, String imageUrl) {
            if (isBound) {
                new DownloadImageTask().execute(imageUrl, title);
            }
        }
    }

    private class DownloadImageTask extends android.os.AsyncTask<String, Void, android.graphics.Bitmap> {
        private String title;

        @Override
        protected android.graphics.Bitmap doInBackground(String... urls) {
            String imageUrl = urls[0];
            title = urls[1];
            android.graphics.Bitmap bitmap = null;
            try {
                java.io.InputStream in = new java.net.URL(imageUrl).openStream();
                bitmap = android.graphics.BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return bitmap;
        }

        @Override
        protected void onPostExecute(android.graphics.Bitmap result) {
            if (isBound) {
                mediaPlaybackService.updateNotification(title, result);
                updateMediaSession(title, result);
            }
        }
    }

    private void updateMediaSession(String title, android.graphics.Bitmap artwork) {
        android.support.v4.media.MediaMetadataCompat.Builder metadataBuilder = new android.support.v4.media.MediaMetadataCompat.Builder();
        metadataBuilder.putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, title);
        metadataBuilder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, artwork);
        mediaSession.setMetadata(metadataBuilder.build());
    }

    private void updatePlaybackState(int state) {
        PlaybackStateCompat.Builder playbackStateBuilder = new PlaybackStateCompat.Builder();
        playbackStateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE);
        playbackStateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0);
        mediaSession.setPlaybackState(playbackStateBuilder.build());
    }
}