package com.zptioning.net;

import java.util.List;

import io.reactivex.rxjava3.core.Single;
import retrofit2.Call;

public class ProxyGitHubService implements GitHubService {
    @Override
    public Call<List<Repo>> listRepos(String user) {
        return null;
    }

    @Override
    public Single<List<Repo>> listReposRx(String user) {
        return null;
    }
}
