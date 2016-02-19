package io.github.carlorodriguez.alarmon;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.webkit.WebView;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_about);

        setTitle(getString(R.string.about));

        WebView webView = (WebView) findViewById(R.id.about_wb);

        webView.loadUrl("file:///android_asset/about.html");
    }

}
