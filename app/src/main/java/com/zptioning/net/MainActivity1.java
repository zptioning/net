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
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity1 extends AppCompatActivity implements View.OnClickListener {
    public static final String TAG = "zp_test";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.tv_animation).setOnClickListener(this);
        initRetrofit();
//        initClone();
        initVolley();
//        initConnectivityManager();
        initRxjava();
    }

    private void initRetrofit() {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();

        GitHubService gitHubService = retrofit.create(GitHubService.class);

        retrofit2.Call<List<Repo>> call = gitHubService.listRepos("octocat");
        call.enqueue(new retrofit2.Callback<List<Repo>>() {
            @Override
            public void onResponse(retrofit2.Call<List<Repo>> call, retrofit2.Response<List<Repo>> response) {
                Log.i(TAG, "onResponse: " + response.body().get(0).getName());
            }

            @Override
            public void onFailure(retrofit2.Call<List<Repo>> call, Throwable t) {
                Log.i(TAG, "onResponse: " + t.getMessage());
            }
        });

        Single<List<Repo>> single = gitHubService.listReposRx("octocat");
        single.subscribe(new Consumer<List<Repo>>() {
            @Override
            public void accept(List<Repo> repos) throws Throwable {

            }
        });
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

    /**
     * 初始化 volley okhttp
     */
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

    private void initRxjava() {
        Observable.just(1).observeOn(Schedulers.computation())
                .subscribeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Integer>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {

                    }

                    @Override
                    public void onNext(@NonNull Integer integer) {

                    }

                    @Override
                    public void onError(@NonNull Throwable e) {

                    }

                    @Override
                    public void onComplete() {

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
