package com.zptioning.net;

import java.util.List;

import retrofit2.Call;

public class ProxyGitHubService implements GitHubService {
    @Override
    public Call<List<Repo>> listRepos(String user) {
        return null;
    }
}
