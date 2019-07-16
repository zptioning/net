package com.zptioning.net;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import com.zptioning.net.section01_animation.AnimationActivity;
import com.zptioning.net.section03_Clone.TestClone;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity1 extends AppCompatActivity implements View.OnClickListener {
    public static final String TAG = "zp_test";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.tv_animation).setOnClickListener(this);
//        initClone();
        initVolley();
//        initConnectivityManager();
    }

    private void initConnectivityManager() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            NetworkInfo networkInfo = connectivityManager.getNetworkInfo(activeNetwork);
            if (null != networkInfo) {
                Log.i("hello", networkInfo.getTypeName() + " " + networkInfo.getType());
            }
        }
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        Network[] allNetworks = connectivityManager.getAllNetworks();

    }

    private void initVolley() {
        OkHttpClient okHttpClient = new OkHttpClient();
        String url = "https://www.baidu.com/";
        final Request request = new Request.Builder().url(url).build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.i("joker", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i("joker", response.body().toString());
            }
        });

        url = "https://www.12306.cn";
        final Request request1 = new Request.Builder().url(url).build();
        Call call1 = okHttpClient.newCall(request1);
        call1.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.i("joker", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i("joker", response.body().toString());
            }
        });
    }

    private void initClone() {
        new TestClone().ShallowCopy1();
        new TestClone().ShallowCopy2();
        new TestClone().DeepCopy1();
        try {
            new TestClone().DeepCopy2();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.tv_animation:
                startActivity(new Intent(this, AnimationActivity.class));
                break;
        }
    }
}
