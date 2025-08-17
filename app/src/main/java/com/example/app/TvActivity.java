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
import android.view.KeyEvent;
import android.view.View;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

public class TvActivity extends Activity implements MediaPlaybackService.MuteStateListener {
    boolean DoublePressToExit = false;
    private AudioManager audioManager;
    private Toast toast;
    private Toast muteToast;
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
        // REMOTE RESOURCE
        mWebView.loadUrl("https://purplescorpion1.github.io/");

        // LOCAL RESOURCE
        // mWebView.loadUrl("file:///android_asset/index.html");

        playbtn = findViewById(R.id.btnplay);
        btnMute = findViewById(R.id.btnmute);

        playbtn.setOnClickListener(btnClickListen);

        handler = new Handler();

        // Find your ImageView (btnmute) by its ID
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        muteToast = Toast.makeText(getApplicationContext(), "", Toast.LENGTH_SHORT);

        // Set a click listener for the Mute button
        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isBound && mediaPlaybackService != null) {
                    if (mediaPlaybackService.isMuted()) {
                        mediaPlaybackService.unmute();
                    } else {
                        mediaPlaybackService.mute();
                    }
                }
            }
        });

        Intent intent = new Intent(this, MediaPlaybackService.class);
        startService(intent);
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
        if (isBound) {
            if (mediaPlaybackService != null) {
                mediaPlaybackService.unregisterMuteStateListener(this);
            }
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    private void updateMuteButton(boolean muted) {
        if (muted) {
            btnMute.setImageResource(R.drawable.unmute_highlighted);
        } else {
            btnMute.setImageResource(R.drawable.mute_highlighted);
        }
    }

    @Override
    public void onMuteStateChanged(final boolean isMuted) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateMuteButton(isMuted);
                muteToast.setText(isMuted ? "Audio has been muted" : "Audio has been unmuted");
                muteToast.show();
            }
        });
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MediaPlaybackService.LocalBinder binder = (MediaPlaybackService.LocalBinder) service;
            mediaPlaybackService = binder.getService();
            isBound = true;
            mediaPlaybackService.registerMuteStateListener(TvActivity.this);
            updateMuteButton(mediaPlaybackService.isMuted());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            if (mediaPlaybackService != null) {
                mediaPlaybackService.unregisterMuteStateListener(TvActivity.this);
            }
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
                } else {
                    mediaPlaybackService.play();
                    playbtn.setImageResource(R.drawable.pause_highlighted);
                    Toast.makeText(TvActivity.this, "Audio is starting. Please wait...", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    @Override
    public void onBackPressed() {
        if (DoublePressToExit){
            Intent intent = new Intent(this, MediaPlaybackService.class);
            stopService(intent);
            finish();
            if (toast != null) {
                toast.cancel();
            }
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MUTE) {
            if (isBound && mediaPlaybackService != null) {
                if (mediaPlaybackService.isMuted()) {
                    mediaPlaybackService.unmute();
                } else {
                    mediaPlaybackService.mute();
                }
            }
            return true; // Consume the event
        }
        return super.onKeyDown(keyCode, event);
    }

}