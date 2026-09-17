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
    public final ExecutorService executor = Executors.newSingleThreadExecutor();
    public final ExecutorService linkExecutor = Executors.newSingleThreadExecutor();
    public WatlisViewModel(@NonNull Application application) {
        super(application);
        repository = new WatlisRepository(WatlisDatabase.get(application), CoverStore.get(application));
    }
    @Override protected void onCleared() { executor.shutdown(); linkExecutor.shutdownNow(); super.onCleared(); }
}
