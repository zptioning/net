package com.zptioning.net;

import com.zptioning.net.Repo;

import java.util.List;

import io.reactivex.rxjava3.core.Single;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

public interface GitHubService {
    @GET("users/{user}/repos")
    Call<List<Repo>> listRepos(@Path("user") String user);

    /* zp add 兼容RxJava */
    @GET("users/{user}/repos")
    Single<List<Repo>> listReposRx(@Path("user") String user);
}
