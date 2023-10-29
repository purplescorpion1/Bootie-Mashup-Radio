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
import android.widget.SeekBar;
import android.widget.Toast;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.View;
import android.widget.Button;

public class MainActivity extends Activity {
    boolean DoublePressToExit = false;
    Toast toast;
    // creating a variable for
    // button and media player
    Button playBtn, pauseBtn;

    ImageView playbtn;
    SeekBar seekBar;

    MediaPlayer mediaPlayer;

    Runnable runnable;

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

        playbtn.setOnClickListener(btnClickListen);

        mediaPlayer = new MediaPlayer();

        handler = new Handler();

    }

    private View.OnClickListener btnClickListen = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
                playbtn.setImageResource(R.drawable.play);
                Toast.makeText(MainActivity.this, "Audio has been paused", Toast.LENGTH_SHORT).show();
            } else {
                playbtn.setImageResource(R.drawable.pause);
                PlaySong();
                Toast.makeText(MainActivity.this, "Audio is starting. Please wait...", Toast.LENGTH_SHORT).show();
            }
        }
        };

    public void PlaySong(){
        Uri uri = Uri.parse("https://c7.radioboss.fm:18205/stream");
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.reset();

        try {
            mediaPlayer.setDataSource(MainActivity.this, uri);
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