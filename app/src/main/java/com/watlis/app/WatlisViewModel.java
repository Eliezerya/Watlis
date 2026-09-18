package com.watlis.app;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import com.watlis.app.data.WatlisDatabase;
import com.watlis.app.data.WatlisRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WatlisViewModel extends AndroidViewModel {
    public final WatlisRepository repository;
    final ImportProviderStore importProviders;
    public final ExecutorService executor = Executors.newSingleThreadExecutor();
    public final ExecutorService linkExecutor = Executors.newSingleThreadExecutor();
    public WatlisViewModel(@NonNull Application application) {
        super(application);
        repository = new WatlisRepository(WatlisDatabase.get(application), CoverStore.get(application));
        new com.watlis.app.data.SyncEngine(WatlisDatabase.get(application), repository, application.getNoBackupFilesDir(), application.getFilesDir());
        importProviders = new ImportProviderStore(application);
    }
    @Override protected void onCleared() { executor.shutdown(); linkExecutor.shutdownNow(); super.onCleared(); }
}
