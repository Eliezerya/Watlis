package com.watlis.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.watlis.app.data.CharacterEntity;
import com.watlis.app.data.GenreEntity;
import com.watlis.app.data.MediaEntity;
import com.watlis.app.data.MediaTypeEntity;
import com.watlis.app.data.StoryMemoryEntity;
import com.watlis.app.data.UserProgressEntity;
import com.watlis.app.data.ProgressHistoryEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.BLACK, SURFACE = Color.rgb(16, 20, 18), SURFACE_HIGH = Color.rgb(27, 33, 29), BORDER = Color.rgb(48, 57, 50), TEXT = Color.rgb(243, 246, 241), MUTED = Color.rgb(166, 176, 168), ACCENT = Color.rgb(199, 237, 154), DANGER = Color.rgb(255, 170, 166);
    private WatlisViewModel viewModel;
    private LinearLayout content;
    private ActivityResultLauncher<String[]> imagePicker;
    private ActivityResultLauncher<String[]> characterImagePicker;
    private Bundle characterDraft;
    private TextInputEditText[] characterFields;
    private androidx.appcompat.app.AlertDialog characterDialog;
    private ActivityResultLauncher<String> exportPicker;
    private ActivityResultLauncher<String[]> importPicker;
    private String selectedImageUri;
    private String pendingPickedCover;
    private float editorCoverX = 0.5f, editorCoverY = 0.5f;
    private float editorCoverZoom = 1f;
    private boolean editorSaving;
    private ProgressUndoBar progressUndoBar;
    private ShinigamiImporter chapterImporter;
    private java.util.concurrent.Future<?> chapterImportTask;
    java.util.function.Supplier<ShinigamiImporter> chapterImporterFactory = ShinigamiImporter::new;
    private TextView editorImportStatus;
    private TextInputLayout editorTitleBox;
    private String importedTypeName;
    private final Set<String> importedGenreNames = new java.util.LinkedHashSet<>();
    private TextView editorSaveButton;
    private TextView editorPositionSummary;
    private long selectedGenreFilter = -1;
    private String selectedStatusFilter = "all", selectedTypeFilter = "all", selectedSort = "updated";
    private boolean favoritesOnly;
    private final Set<String> typeFilters = new HashSet<>(), statusFilters = new HashSet<>();
    private final Set<Long> genreFilters = new HashSet<>();
    private String query = "";
    private boolean reverseSort;
    private String screen = "home", detailOrigin = "home", editorOrigin = "home";
    private long detailId;
    private Long editingId;
    private android.os.Bundle formDraft;
    private String formBaseline;
    private LinearLayout editorForm, editorGenreContainer, homeList, activeFilters;
    private TextInputEditText editorTitle, editorCover, editorProgress, editorRating, editorNotes;
    private Spinner editorType, editorRelease, editorTracking;
    private android.widget.CheckBox editorFavorite;
    private Set<Long> editorGenres;
    private View editorPreview;
    private TextView resultCount, detailUpdatedLabel, filterButton;
    private ScrollView homeScroll;
    private int homeScrollY;
    private androidx.drawerlayout.widget.DrawerLayout drawer;
    private android.widget.FrameLayout drawerPanel;
    private String pendingRestoreScreen = "home";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        viewModel = new ViewModelProvider(this).get(WatlisViewModel.class);
        imagePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                pendingPickedCover = uri.toString();
                if (screen.equals("editor") && editorCover != null) applyPickedCover();
            } catch (SecurityException error) {
                toast("Unable to keep access to this image. Choose another file.");
            }
        });
        characterImagePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || characterDraft == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                characterDraft.putString("image", uri.toString());
                if (characterFields != null) characterFields[3].setText(uri.toString());
            } catch (SecurityException error) {
                toast("Unable to keep access to this image. Choose another file.");
            }
        });
        exportPicker = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            if (uri == null) return;
            write(() -> {
                try {
                    String json = viewModel.repository.exportToJson();
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null)
                        throw new IllegalArgumentException("Could not write to the selected location");
                    os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.close();
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException("Failed to write export file");
                }
            }, () -> toast("Data exported successfully"));
        });
        importPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            new MaterialAlertDialogBuilder(this)
                        .setTitle("Import data?")
                        .setMessage("This will replace all your current data with the imported backup. This action cannot be undone.\n\nConsider exporting your current data first.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Import", (d, w) ->
                                write(() -> {
                                    try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                                        if (input == null) throw new IllegalArgumentException("Could not read the selected file");
                                        String json = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                        viewModel.repository.importFromJson(json);
                                    } catch (java.io.IOException error) {
                                        throw new IllegalArgumentException("Could not read the selected file", error);
                                    }
                                }, () -> {
                                    toast("Data imported successfully");
                                    formDraft = null;
                                    formBaseline = null;
                                    clearFilters();
                                    showHome();
                                })).show();
        });
        if (savedInstanceState != null) {
            query = savedInstanceState.getString("query", "");
            selectedSort = savedInstanceState.getString("sort", "updated");
            reverseSort = savedInstanceState.getBoolean("reverse");
            favoritesOnly = savedInstanceState.getBoolean("favorites");
            typeFilters.addAll(savedInstanceState.getStringArrayList("types"));
            statusFilters.addAll(savedInstanceState.getStringArrayList("statuses"));
            for (long id : savedInstanceState.getLongArray("genres")) genreFilters.add(id);
            homeScrollY = savedInstanceState.getInt("scroll");
            detailId = savedInstanceState.getLong("detail");
            detailOrigin = savedInstanceState.getString("origin", "home");
            editorOrigin = savedInstanceState.getString("editorOrigin", "home");
            pendingRestoreScreen = savedInstanceState.getString("screen", "home");
            editingId = savedInstanceState.getLong("editing", 0) == 0 ? null : savedInstanceState.getLong("editing");
            formDraft = savedInstanceState.getBundle("draft");
            formBaseline = savedInstanceState.getString("baseline");
            characterDraft = savedInstanceState.getBundle("characterDraft");
        }
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer != null && drawer.isDrawerOpen(androidx.core.view.GravityCompat.START))
                    drawer.closeDrawers();
                else if (screen.equals("home")) finish();
                else navigateBack();
            }
        });
        LinearLayout loading = shell("Watlis", "Loading your collection", false, false);
        android.widget.ProgressBar indicator = new android.widget.ProgressBar(this);
        content.addView(indicator, lp(-1, dp(48)));
        install(loading);
        write(() -> viewModel.repository.refresh(), () -> {
            switch (pendingRestoreScreen) {
                case "detail":
                    showDetail(detailId);
                    break;
                case "editor":
                    showEditor(editingId);
                    break;
                case "genres":
                    showGenres();
                    break;
                case "types":
                    showMediaTypes();
                    break;
                case "stats":
                    showStats();
                    break;
                default:
                    showHome();
            }
            if (characterDraft != null) {
                long mediaId = characterDraft.getLong("mediaId"), characterId = characterDraft.getLong("id");
                CharacterEntity existing = null;
                for (CharacterEntity c : viewModel.repository.characters(mediaId)) if (c.id == characterId) existing = c;
                if (viewModel.repository.media(mediaId) != null && (characterId == 0 || existing != null))
                    showCharacterDialog(mediaId, existing);
                else characterDraft = null;
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putString("screen", screen);
        out.putString("query", query);
        out.putString("sort", selectedSort);
        out.putBoolean("reverse", reverseSort);
        out.putBoolean("favorites", favoritesOnly);
        out.putStringArrayList("types", new ArrayList<>(typeFilters));
        out.putStringArrayList("statuses", new ArrayList<>(statusFilters));
        out.putLongArray("genres", genreFilters.stream().mapToLong(Long::longValue).toArray());
        out.putInt("scroll", homeScroll == null ? homeScrollY : homeScroll.getScrollY());
        out.putLong("detail", detailId);
        out.putString("origin", detailOrigin);
        out.putString("editorOrigin", editorOrigin);
        out.putLong("editing", editingId == null ? 0 : editingId);
        if (screen.equals("editor")) out.putBundle("draft", captureDraft());
        out.putString("baseline", formBaseline);
        captureCharacterDraft();
        if (characterDraft != null) out.putBundle("characterDraft", new Bundle(characterDraft));
        super.onSaveInstanceState(out);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private void margin(View v, int l, int t, int r, int b) {
        if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            p.setMargins(dp(l), dp(t), dp(r), dp(b));
            v.setLayoutParams(p);
        }
    }

    private TextView label(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView title(String s) {
        TextView v = label(s, 20, TEXT);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextView muted(String s) {
        return label(s, 12, MUTED);
    }

    private android.graphics.drawable.GradientDrawable bg(int color, float radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private android.graphics.drawable.GradientDrawable outlined(int color, int stroke, float radius) {
        android.graphics.drawable.GradientDrawable d = bg(color, radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView action(String s, View.OnClickListener c) {
        TextView v = label(s, 13, TEXT);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setMinHeight(dp(48));
        v.setBackground(outlined(SURFACE_HIGH, BORDER, 10));
        v.setOnClickListener(c);
        return v;
    }

    private TextView accentAction(String s, View.OnClickListener c) {
        TextView v = label(s, 13, Color.BLACK);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setPadding(dp(14), 0, dp(14), 0);
        v.setMinHeight(dp(48));
        v.setBackground(bg(ACCENT, 10));
        v.setOnClickListener(c);
        return v;
    }

    private void add(LinearLayout p, View v, int top, int bottom) {
        p.addView(v, lp(-1, -2));
        margin(v, 0, top, 0, bottom);
    }

    private ScrollView scroll(LinearLayout p) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        s.addView(p);
        return s;
    }

    private LinearLayout shell(String heading, String subheading, boolean back, boolean nav) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView button = action(back ? "‹" : "☰", v -> {
            if (back) navigateBack();
            else drawer.openDrawer(androidx.core.view.GravityCompat.START);
        });
        button.setTextSize(24);
        button.setContentDescription(back ? "Back" : "Open navigation drawer");
        top.addView(button, lp(dp(48), dp(48)));
        margin(button, 0, 0, 12, 0);
        top.addView(title(heading), lp(0, -2, 1));
        root.addView(top, lp(-1, -2));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, lp(-1, 0, 1));
        return root;
    }

    private LinearLayout bottomNav() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(20), dp(48), dp(20), dp(24));
        menu.setBackgroundColor(SURFACE);
        add(menu, title("Watlis"), 0, 24);
        add(menu, action("My media  ·  " + viewModel.repository.media().size(), v -> {
            clearFilters();
            drawer.closeDrawers();
            showHome();
        }), 0, 8);
        add(menu, action("Favorites  ·  " + viewModel.repository.favoriteCount(), v -> {
            clearFilters();
            favoritesOnly = true;
            drawer.closeDrawers();
            showHome();
        }), 0, 16);
        add(menu, action("Genres", v -> {
            drawer.closeDrawers();
            showGenres();
        }), 0, 8);
        add(menu, action("Statistics", v -> {
            drawer.closeDrawers();
            showStats();
        }), 0, 16);
        add(menu, action("Media types", v -> {
            drawer.closeDrawers();
            showMediaTypes();
        }), 0, 16);
        add(menu, action("Export data", v -> {
            drawer.closeDrawers();
            exportData();
        }), 0, 8);
        add(menu, action("Import data", v -> {
            drawer.closeDrawers();
            importData();
        }), 0, 24);
        add(menu, action("Close", v -> drawer.closeDrawers()), 0, 0);
        int selected = screen.equals("genres") ? 3 : screen.equals("stats") ? 4 : screen.equals("types") ? 5 : favoritesOnly ? 2 : 1;
        menu.getChildAt(selected).setBackground(outlined(SURFACE_HIGH, ACCENT, 10));
        return menu;
    }

    private TextView navItem(String s, View.OnClickListener c) {
        TextView v = label(s, 11, MUTED);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setOnClickListener(c);
        return v;
    }

    private void showHome() {
        screen = "home";
        LinearLayout root = shell("Watlis", "", false, true);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(20), dp(20), dp(20), dp(12));
        TextView h = title("My media");
        h.setTextSize(28);
        heading.addView(h, lp(0, -2, 1));
        heading.addView(accentAction("+ Add", v -> showEditor(null)), lp(-2, dp(48)));
        content.addView(heading);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setPadding(dp(20), 0, dp(20), 0);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(16);
        search.setHint("Search your titles");
        search.setContentDescription("Search your titles");
        android.graphics.drawable.Drawable searchIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_search);
        if (searchIcon != null) {
            searchIcon.setBounds(0, 0, dp(20), dp(20));
            search.setCompoundDrawables(searchIcon, null, null, null);
            search.setCompoundDrawablePadding(dp(8));
        }
        search.setBackground(outlined(SURFACE, BORDER, 10));
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setText(query);
        searchRow.addView(search, lp(0, dp(48), 1));
        TextView clear = action("×", v -> search.setText(""));
        clear.setContentDescription("Clear search");
        clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        searchRow.addView(clear, lp(dp(48), dp(48)));
        margin(clear, 8, 0, 0, 0);
        content.addView(searchRow);
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(dp(20), dp(8), dp(20), 0);
        filterButton = action("Filter", v -> showFilterDialog());
        tools.addView(filterButton, lp(-2, dp(48)));
        tools.addView(action("Sort", v -> showSortDialog()), lp(-2, dp(48)));
        margin(tools.getChildAt(1), 8, 0, 0, 0);
        resultCount = muted("");
        resultCount.setPadding(dp(12), 0, 0, 0);
        tools.addView(resultCount, lp(0, dp(48), 1));
        content.addView(tools);
        activeFilters = new LinearLayout(this);
        activeFilters.setOrientation(LinearLayout.VERTICAL);
        activeFilters.setPadding(dp(20), 0, dp(20), 0);
        content.addView(activeFilters);
        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        homeList.setPadding(dp(20), dp(12), dp(20), dp(24));
        homeScroll = scroll(homeList);
        content.addView(homeScroll, lp(-1, 0, 1));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                homeScrollY = 0;
                loadHome();
            }

            public void afterTextChanged(android.text.Editable e) {
            }
        });
        install(root);
        loadHome();
        homeScroll.post(() -> homeScroll.scrollTo(0, homeScrollY));
    }

    private void loadHome() {
        if (screen.equals("home"))
            renderHome(viewModel.repository.media(), viewModel.repository.genres());
    }

    private void renderHome(List<MediaEntity> media, List<GenreEntity> allGenres) {
        if (!screen.equals("home") || homeList == null) return;
        List<MediaEntity> filtered = filteredMedia(typeFilters, statusFilters, genreFilters, favoritesOnly);
        sortMedia(filtered);
        homeList.removeAllViews();
        resultCount.setText(filtered.size() + (filtered.size() == 1 ? " title" : " titles"));
        int count = typeFilters.size() + statusFilters.size() + genreFilters.size() + (favoritesOnly ? 1 : 0);
        filterButton.setText(count == 0 ? "Filter" : "Filter · " + count);
        activeFilters.removeAllViews();
        com.google.android.material.chip.ChipGroup chips = new com.google.android.material.chip.ChipGroup(this);
        for (String type : new HashSet<>(typeFilters))
            filterChip(chips, typeName(type), () -> {
                typeFilters.remove(type);
                loadHome();
            });
        for (String status : new HashSet<>(statusFilters))
            filterChip(chips, statusLabel(status, null), () -> {
                statusFilters.remove(status);
                loadHome();
            });
        for (GenreEntity genre : allGenres)
            if (genreFilters.contains(genre.id)) filterChip(chips, genre.name, () -> {
                genreFilters.remove(genre.id);
                loadHome();
            });
        if (favoritesOnly) filterChip(chips, "Favorites", () -> {
            favoritesOnly = false;
            loadHome();
        });
        if (chips.getChildCount() > 0) {
            activeFilters.addView(chips);
            add(activeFilters, action("Clear all", v -> {
                clearFilters();
                loadHome();
            }), 0, 0);
        }
        if (filtered.isEmpty()) {
            TextView h = title(media.isEmpty() ? "Your next story starts here" : "No matching titles");
            h.setGravity(Gravity.CENTER);
            add(homeList, h, 48, 12);
            TextView explanation = muted(media.isEmpty() ? "Keep track of every chapter and episode. Add your first title to begin." : "Try another search or adjust your filters.");
            explanation.setGravity(Gravity.CENTER);
            add(homeList, explanation, 0, 16);
            add(homeList, accentAction(media.isEmpty() ? "Add media" : "Clear search & filters", v -> {
                if (media.isEmpty()) showEditor(null);
                else {
                    query = "";
                    clearFilters();
                    showHome();
                }
            }), 0, 0);
        } else for (MediaEntity m : filtered) addMediaRow(homeList, m);
    }

    private int rating(long id) {
        UserProgressEntity p = viewModel.repository.progress(id);
        return p == null || p.rating == null ? 0 : p.rating;
    }

    private void addMediaRow(LinearLayout list, MediaEntity m) {
        UserProgressEntity p = viewModel.repository.progress(m.id);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int accent = mediaAccent(m);
        row.setPadding(dp(12), dp(14), dp(12), dp(12));
        row.setBackground(outlined(tint(SURFACE, accent, .035f), tint(BORDER, accent, .18f), 16));
        LinearLayout upper = new LinearLayout(this);
        upper.setGravity(Gravity.CENTER_VERTICAL);
        View cover = coverView(m.coverImage, m.title, 56, 80, m.coverPositionX, m.coverPositionY, m.coverZoom);
        cover.setOnClickListener(v -> openDetail(m.id));
        upper.addView(cover, lp(dp(56), dp(80)));
        margin(cover, 0, 0, 10, 0);
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setMinimumHeight(dp(80));
        identity.setOnClickListener(v -> openDetail(m.id));
        identity.setContentDescription("Open details for " + m.title);
        TextView name = label(m.title, 16, TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setOnClickListener(v -> openDetail(m.id));
        identity.addView(name, lp(-1, -2));
        add(identity, muted(typeName(m.type) + " · " + (p.rating == null ? "Unrated" : p.rating + "/10") + (m.isFavorite ? " · ★" : "")), 4, 6);
        com.google.android.material.chip.ChipGroup tags = new com.google.android.material.chip.ChipGroup(this);
        tags.setChipSpacingHorizontal(dp(4));
        tags.setChipSpacingVertical(dp(4));
        tags.setContentDescription("Genres for " + m.title);
        List<GenreEntity> genres = viewModel.repository.genresFor(m.id);
        int tagHeight = Math.max(dp(28), Math.round(12 * getResources().getDisplayMetrics().scaledDensity * 1.4f) + dp(8));
        for (int i = 0; i < Math.min(2, genres.size()); i++) {
            TextView tag = chip(genres.get(i).name, color(genres.get(i).color));
            tag.setSingleLine(true);
            tag.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tag.setMaxWidth(dp(120));
            tags.addView(tag, new ViewGroup.LayoutParams(-2, tagHeight));
        }
        if (genres.size() > 2) {
            TextView moreGenres = muted("+" + (genres.size() - 2));
            moreGenres.setGravity(Gravity.CENTER);
            moreGenres.setPadding(dp(4), 0, dp(4), 0);
            tags.addView(moreGenres, new ViewGroup.LayoutParams(-2, tagHeight));
        }
        identity.addView(tags);
        upper.addView(identity, lp(0, -2, 1));
        TextView menu = action("⋮", v -> mediaMenu(v, m));
        menu.setTextSize(22);
        menu.setContentDescription("Actions for " + m.title);
        upper.addView(menu, lp(dp(48), dp(48)));
        margin(menu, 8, 0, 0, 0);
        ((LinearLayout.LayoutParams) menu.getLayoutParams()).gravity = Gravity.TOP;
        row.addView(upper);
        add(row, muted(statusLabel(p.trackingStatus, m.type)), 8, 4);
        row.addView(progressControls(m, p), lp(-1, -2));
        list.addView(row, lp(-1, -2));
        margin(row, 0, 0, 0, 12);
    }

    private TextView progressButton(String s) {
        TextView v = action(s, null);
        v.setTextSize(22);
        v.setTextColor(ACCENT);
        v.setContentDescription(s.equals("+") ? "Increase progress" : "Decrease progress");
        return v;
    }

    private TextView chip(String text, int color) {
        int tinted = androidx.core.graphics.ColorUtils.blendARGB(SURFACE, color, 0.18f);
        TextView view = label(text, 12, TEXT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(4), dp(8), dp(4));
        view.setBackground(outlined(tinted, color, 7));
        int foreground = androidx.core.graphics.ColorUtils.calculateContrast(color, tinted) >= 4.5 ? color : TEXT;
        view.setTextColor(foreground);
        return view;
    }

    private int withAlpha(int c, int a) {
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c));
    }

    private int color(String s) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return ACCENT;
        }
    }

    private String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1).replace('_', ' ');
    }

    private String unit(MediaEntity m) {
        return usesEpisodes(m.type) ? "Episode" : "Chapter";
    }

    private String typeName(String key) {
        if ("imported_type".equals(key) && importedTypeName != null) return importedTypeName;
        MediaTypeEntity type = viewModel.repository.mediaType(key);
        return type == null ? cap(key) : type.name;
    }

    private boolean usesEpisodes(String key) {
        MediaTypeEntity type = viewModel.repository.mediaType(key);
        return type == null ? "anime".equals(key) : type.usesEpisodes;
    }

    private String[] typeKeys() {
        List<MediaTypeEntity> types = viewModel.repository.mediaTypes();
        String[] keys = new String[types.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = types.get(i).key;
        return keys;
    }

    private int tint(int base, int accent, float amount) {
        return androidx.core.graphics.ColorUtils.blendARGB(base, accent, amount);
    }

    private int mediaAccent(MediaEntity media) {
        List<GenreEntity> genres = viewModel.repository.genresFor(media.id);
        int accent = genres.isEmpty() ? ACCENT : color(genres.get(0).color);
        // Keep even very dark genre colors legible against the dark surfaces.
        while (androidx.core.graphics.ColorUtils.calculateContrast(accent, SURFACE_HIGH) < 4.5)
            accent = tint(accent, Color.WHITE, .15f);
        return accent;
    }

    private String formatProgress(MediaEntity m, UserProgressEntity p) {
        return unit(m) + " " + number(p == null ? 0 : p.currentProgress);
    }

    private void changeProgress(long id, double value) {
        ProgressHistoryEntity[] change = {null};
        write(() -> change[0] = viewModel.repository.updateProgress(id, value), () -> {
            if (screen.equals("detail") && detailId == id) showDetail(id);
            else loadHome();
            offerProgressUndo(change[0]);
        });
    }

    private void offerProgressUndo(ProgressHistoryEntity change) {
        if (change == null || !(screen.equals("home") || screen.equals("detail") && detailId == change.mediaId)) return;
        MediaEntity changedMedia = viewModel.repository.media(change.mediaId);
        if (changedMedia == null) return;
        // Reuse the visible bar during rapid taps so its action always matches its latest label.
        if (progressUndoBar == null || !progressUndoBar.isShownOrQueued())
            progressUndoBar = new ProgressUndoBar(findViewById(android.R.id.content));
        progressUndoBar.update(getString(R.string.history_progress, change.unit, number(change.fromProgress), number(change.toProgress)),
                changedMedia.title, mediaAccent(changedMedia), () -> undoProgress(change));
    }

    private void undoProgress(ProgressHistoryEntity change) {
        boolean[] undone = {false};
        write(() -> undone[0] = viewModel.repository.undoProgress(change.mediaId, change.token), () -> {
            if (screen.equals("detail") && detailId == change.mediaId) showDetail(change.mediaId);
            else if (screen.equals("home")) loadHome();
            else if (screen.equals("stats")) showStats();
            toast(undone[0] ? "Progress change undone" : "A newer change exists or this change was already undone. Nothing changed.");
        });
    }

    private void changeProgressOnDetail(long id, double n) {
        changeProgress(id, n);
    }

    private void mediaMenu(View anchor, MediaEntity m) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (screen.equals("stats")) {
            android.view.MenuItem undo = menu.getMenu().add(R.string.history_undo_latest).setEnabled(false);
            // One indexed row, fetched only when opening this menu; never on the startup path.
            viewModel.executor.execute(() -> {
                try {
                    List<ProgressHistoryEntity> latest = viewModel.repository.progressHistory(m.id, Long.MAX_VALUE, 1);
                    ProgressHistoryEntity change = latest.isEmpty() ? null : latest.get(0);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || !anchor.isAttachedToWindow()) return;
                        if (change != null && !"undo".equals(change.kind)) {
                            undo.setEnabled(true);
                            undo.setOnMenuItemClickListener(item -> { undoProgress(change); return true; });
                        }
                    });
                } catch (Exception error) {
                    android.util.Log.e("Watlis", "Could not load latest progress change", error);
                }
            });
        }
        menu.getMenu().add(m.isFavorite ? "Unfavorite" : "Favorite");
        menu.getMenu().add("Edit media");
        menu.getMenu().add("Delete media");
        menu.setOnMenuItemClickListener(item -> {
            String selected = item.getTitle().toString();
            if (selected.equals("Edit media")) showEditor(m.id);
            else if (selected.equals("Delete media")) confirmDelete(m);
            else write(() -> viewModel.repository.favorite(m.id), this::refreshScreen);
            return true;
        });
        menu.show();
    }

    private List<Long> ids(List<GenreEntity> gs) {
        ArrayList<Long> r = new ArrayList<>();
        for (GenreEntity g : gs) r.add(g.id);
        return r;
    }

    private void confirmDelete(MediaEntity m) {
        new MaterialAlertDialogBuilder(this).setTitle("Delete “" + m.title + "”?")
                .setMessage("This removes its progress, story memory, characters, and genre links.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) ->
                        write(() -> viewModel.repository.deleteMedia(m), () -> {
                            if (screen.equals("stats")) showStats();
                            else showHome();
                        })).show();
    }

    private TextInputEditText field(LinearLayout form, String hint, String value, boolean multi) {
        TextInputLayout box = new TextInputLayout(this);
        box.setHint(hint);
        box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        box.setBoxStrokeColor(BORDER);
        box.setHintTextColor(android.content.res.ColorStateList.valueOf(MUTED));
        TextInputEditText e = new TextInputEditText(this);
        e.setText(value == null ? "" : value);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(14);
        e.setSingleLine(!multi);
        e.setInputType(multi ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_CLASS_TEXT);
        if (multi) e.setMinLines(3);
        box.addView(e, lp(-1, multi ? dp(106) : dp(58)));
        form.addView(box, lp(-1, -2));
        margin(box, 0, 0, 0, 10);
        return e;
    }

    private Spinner spinner(LinearLayout form, String[] choices, String selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, choices) {
            private View render(int position, View convert, ViewGroup parent, boolean dropdown) {
                TextView v = (TextView) (dropdown ? super.getDropDownView(position, convert, parent) : super.getView(position, convert, parent));
                String raw = choices[position];
                v.setText(raw.equals("reading") || raw.equals("plan_to_read") ? statusLabel(raw, editorType == null ? null : editorType.getSelectedItem().toString()) : typeName(raw));
                v.setTextColor(TEXT);
                v.setTextSize(16);
                v.setMinHeight(dp(48));
                v.setPadding(dp(12), dp(8), dp(12), dp(8));
                v.setBackground(bg(SURFACE_HIGH, 10));
                return v;
            }

            @Override
            public View getView(int p, View c, ViewGroup parent) {
                return render(p, c, parent, false);
            }

            @Override
            public View getDropDownView(int p, View c, ViewGroup parent) {
                return render(p, c, parent, true);
            }
        };
        spinner.setAdapter(adapter);
        int index = Arrays.asList(choices).indexOf(selected);
        if (index >= 0) spinner.setSelection(index);
        add(form, spinner, 0, 12);
        return spinner;
    }

    private void showEditor(Long editId) {
        cancelChapterImport();
        if (!screen.equals("editor")) {
            editorOrigin = screen;
            if (homeScroll != null && screen.equals("home")) homeScrollY = homeScroll.getScrollY();
        }
        screen = "editor";
        editingId = editId;
        MediaEntity m = editId == null ? null : viewModel.repository.media(editId);
        UserProgressEntity p = m == null ? new UserProgressEntity() : viewModel.repository.progress(m.id);
        android.os.Bundle draft = formDraft;
        formDraft = null;
        importedTypeName = draft == null ? null : draft.getString("importedType");
        importedGenreNames.clear();
        if (draft != null && draft.getStringArrayList("importedGenres") != null)
            importedGenreNames.addAll(draft.getStringArrayList("importedGenres"));
        LinearLayout root = shell(m == null ? "Add media" : "Edit media", "", true, false);
        editorForm = new LinearLayout(this);
        editorForm.setOrientation(LinearLayout.VERTICAL);
        editorForm.setPadding(dp(20), dp(12), dp(20), dp(24));
        content.addView(scroll(editorForm), lp(-1, 0, 1));
        editorTitle = field(editorForm, "Title", draft == null ? (m == null ? "" : m.title) : draft.getString("title"), false);
        if (m == null) {
            editorTitleBox = (TextInputLayout) editorTitle.getParent().getParent();
            editorTitleBox.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
            editorTitleBox.setEndIconDrawable(R.drawable.ic_import_link);
            editorTitleBox.setEndIconContentDescription("Fill from Shinigami chapter link");
            editorTitleBox.setEndIconOnClickListener(v -> importChapterLink());
            editorTitleBox.setEndIconVisible(ShinigamiImporter.chapterId(text(editorTitle)) != null);
            editorTitle.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    editorTitleBox.setEndIconVisible(chapterImporter == null && ShinigamiImporter.chapterId(s.toString()) != null);
                }
                public void afterTextChanged(android.text.Editable s) { }
            });
            editorImportStatus = muted(draft == null ? "Paste a Shinigami chapter link to fill this form." :
                    draft.getString("importMessage", "Paste a Shinigami chapter link to fill this form."));
            editorImportStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            if (editorImportStatus.getText().toString().equals(getString(R.string.chapter_import_loading)))
                editorImportStatus.setText(R.string.chapter_import_interrupted);
            add(editorForm, editorImportStatus, 0, 12);
        }
        add(editorForm, muted("Media type"), 0, 4);
        List<String> editorTypes = new ArrayList<>(Arrays.asList(typeKeys()));
        if (importedTypeName != null) editorTypes.add("imported_type");
        editorType = spinner(editorForm, editorTypes.toArray(new String[0]), draft == null ? (m == null ? "manga" : m.type) : draft.getString("type"));
        editorType.setContentDescription("Media type");
        add(editorForm, action("+ New media type", v -> showMediaTypeDialog(null)), 0, 12);
        add(editorForm, muted("Release status"), 0, 4);
        editorRelease = spinner(editorForm, new String[]{"ongoing", "completed"}, draft == null ? (m == null ? "ongoing" : m.releaseStatus) : draft.getString("release"));
        editorGenres = new HashSet<>(m == null ? new ArrayList<>() : ids(viewModel.repository.genresFor(m.id)));
        if (draft != null) {
            editorGenres.clear();
            for (long id : draft.getLongArray("genres")) editorGenres.add(id);
        }
        add(editorForm, sectionTitle("Genres"), 8, 4);
        editorGenreContainer = new LinearLayout(this);
        editorGenreContainer.setOrientation(LinearLayout.VERTICAL);
        editorForm.addView(editorGenreContainer);
        renderEditorGenres();
        add(editorForm, action("+ New genre", v -> showGenreDialog(null)), 4, 16);
        editorCover = field(editorForm, "Image URL", draft == null ? (m == null ? "" : m.coverImage) : draft.getString("cover"), false);
        selectedImageUri = text(editorCover);
        editorCoverX = draft == null ? (m == null ? 0.5f : m.coverPositionX) : draft.getFloat("coverX", 0.5f);
        editorCoverY = draft == null ? (m == null ? 0.5f : m.coverPositionY) : draft.getFloat("coverY", 0.5f);
        editorCoverZoom = draft == null ? (m == null ? 1f : m.coverZoom) : draft.getFloat("coverZoom", 1f);
        add(editorForm, muted("A small cover copy is saved in Watlis. Full-screen preview uses the original when available."), 0, 8);
        add(editorForm, muted("List thumbnail"), 4, 8);
        editorPreview = coverView(selectedImageUri, "List thumbnail preview", 100, 144, editorCoverX, editorCoverY, editorCoverZoom);
        editorForm.addView(editorPreview, lp(dp(100), dp(144)));
        editorPositionSummary = muted(positionSummary(editorCoverX, editorCoverY));
        add(editorForm, editorPositionSummary, 8, 12);
        add(editorForm, action("Adjust cover position", v -> showCoverPositionEditor()), 0, 12);
        add(editorForm, action("Device image", v -> imagePicker.launch(new String[]{"image/*"})), 0, 8);
        add(editorForm, action("Preview URL", v -> updateCoverPreview()), 0, 8);
        add(editorForm, action("Remove cover", v -> {
            editorCover.setText("");
            selectedImageUri = null;
            updateCoverPreview();
        }), 0, 16);
        editorProgress = field(editorForm, "Current progress", draft == null ? number(p.currentProgress) : draft.getString("progress"), false);
        editorProgress.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        editorRating = field(editorForm, "Rating (1–10, blank for Unrated)", draft == null ? (p.rating == null ? "" : p.rating.toString()) : draft.getString("rating"), false);
        editorRating.setInputType(InputType.TYPE_CLASS_NUMBER);
        add(editorForm, muted("Tracking status"), 0, 4);
        editorTracking = spinner(editorForm, new String[]{"plan_to_read", "reading", "completed", "dropped"}, draft == null ? p.trackingStatus : draft.getString("tracking"));
        editorType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }

            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                ((TextInputLayout) editorProgress.getParent().getParent()).setHint(usesEpisodes(parent.getItemAtPosition(position).toString()) ? "Current episode" : "Current chapter");
                ((ArrayAdapter<?>) editorTracking.getAdapter()).notifyDataSetChanged();
            }
        });
        editorNotes = field(editorForm, "Personal notes", draft == null ? p.notes : draft.getString("notes"), true);
        editorFavorite = new android.widget.CheckBox(this);
        editorFavorite.setText("Favorite");
        editorFavorite.setTextColor(TEXT);
        editorFavorite.setMinHeight(dp(48));
        editorFavorite.setChecked(draft == null ? (m != null && m.isFavorite) : draft.getBoolean("favorite"));
        editorForm.addView(editorFavorite);
        TextView save = accentAction(m == null ? "Add media" : "Save changes", v -> saveEditor(m));
        editorSaveButton = save;
        editorSaving = false;
        add(editorForm, save, 16, 0);
        if (draft == null || formBaseline == null) formBaseline = draftKey(captureDraft());
        install(root);
        if (pendingPickedCover != null) editorForm.post(this::applyPickedCover);
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void importChapterLink() {
        if (chapterImporter != null || editorSaving || editingId != null) return;
        String link = text(editorTitle);
        if (ShinigamiImporter.chapterId(link) == null) return;
        Bundle before = captureDraft();
        String beforeKey = draftKey(before);
        TextInputEditText target = editorTitle;
        ShinigamiImporter importer = chapterImporterFactory.get();
        chapterImporter = importer;
        editorTitleBox.setEndIconVisible(false);
        editorImportStatus.setText(R.string.chapter_import_loading);
        chapterImportTask = viewModel.linkExecutor.submit(() -> {
            try {
                ShinigamiImporter.Result result = importer.fetch(link);
                runOnUiThread(() -> {
                    if (!activeChapterImport(importer, target)) return;
                    chapterImporter = null; chapterImportTask = null;
                    editorTitleBox.setEndIconVisible(ShinigamiImporter.chapterId(text(editorTitle)) != null);
                    if (editorSaving || !beforeKey.equals(draftKey(captureDraft()))) {
                        editorImportStatus.setText(R.string.chapter_import_changed);
                        return;
                    }
                    applyChapterImport(result, before);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!activeChapterImport(importer, target)) return;
                    chapterImporter = null; chapterImportTask = null;
                    editorTitleBox.setEndIconVisible(ShinigamiImporter.chapterId(text(editorTitle)) != null);
                    String reason = error instanceof java.net.SocketTimeoutException ? "The service timed out." :
                            error instanceof java.net.UnknownHostException ? "Could not connect. Check your internet connection." :
                            error.getMessage() == null ? "Could not fetch metadata." : error.getMessage();
                    editorImportStatus.setText(getString(R.string.chapter_import_error, reason));
                });
            }
        });
    }

    private boolean activeChapterImport(ShinigamiImporter importer, TextInputEditText target) {
        return !isDestroyed() && !isFinishing() && screen.equals("editor") && editingId == null
                && editorTitle == target && chapterImporter == importer;
    }

    private void applyChapterImport(ShinigamiImporter.Result result, Bundle draft) {
        draft.putString("title", result.title);
        draft.putString("progress", number(result.progress));
        draft.putString("tracking", "reading");
        if (!result.cover.isEmpty()) {
            draft.putString("cover", result.cover);
            draft.putFloat("coverX", .5f); draft.putFloat("coverY", .5f); draft.putFloat("coverZoom", 1f);
        }
        if (result.releaseStatus != null) draft.putString("release", result.releaseStatus);
        if (!result.format.isEmpty()) {
            String key = null;
            for (MediaTypeEntity type : viewModel.repository.mediaTypes())
                if (type.name.equalsIgnoreCase(result.format) || type.key.equalsIgnoreCase(result.format)) { key = type.key; break; }
            draft.putString("type", key == null ? "imported_type" : key);
            draft.putString("importedType", key == null ? result.format : null);
        }
        Set<Long> genres = new HashSet<>();
        for (long id : draft.getLongArray("genres")) genres.add(id);
        Set<String> missing = new java.util.LinkedHashSet<>(importedGenreNames);
        for (String name : result.genres) {
            GenreEntity genre = viewModel.repository.findGenre(name);
            if (genre == null) missing.add(name); else genres.add(genre.id);
        }
        draft.putLongArray("genres", genres.stream().mapToLong(Long::longValue).toArray());
        draft.putStringArrayList("importedGenres", new ArrayList<>(missing));
        String notes = draft.getString("notes", "");
        draft.putString("notes", notes.isEmpty() ? result.notes : notes + "\n\n" + result.notes);
        String message = "Draft filled · Chapter " + number(result.progress) + ". Review and tap Add media to save.";
        if (result.releaseStatus == null) message += " Check release status; the source uses numeric status codes.";
        if (result.cover.isEmpty()) message += " No supported cover was returned.";
        draft.putString("importMessage", message);
        formDraft = draft;
        showEditor(null);
    }

    private void cancelChapterImport() {
        if (chapterImporter != null) chapterImporter.cancel();
        if (chapterImportTask != null) chapterImportTask.cancel(true);
        chapterImporter = null; chapterImportTask = null;
    }

    @Override protected void onDestroy() {
        cancelChapterImport();
        super.onDestroy();
    }

    private double parseDouble(String value, double fallback) {
        String normalized = value.trim().replace(',', '.');
        if (normalized.isEmpty()) throw new IllegalArgumentException("Enter a progress value");
        double result;
        try {
            result = Double.parseDouble(normalized);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter a valid number");
        }
        com.watlis.app.data.WatlisRepository.validateProgress(result);
        return result;
    }

    private Integer parseRating(String value) {
        if (value.trim().isEmpty()) return null;
        try {
            int n = Integer.parseInt(value.trim());
            if (n >= 1 && n <= 10) return n;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Rating must be between 1 and 10");
    }

    private void showDetail(long id) {
        MediaEntity m = viewModel.repository.media(id);
        if (m == null) {
            showHome();
            return;
        }
        screen = "detail";
        detailId = id;
        UserProgressEntity p = viewModel.repository.progress(id);
        LinearLayout root = shell("Media details", "", true, false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), dp(24));
        content.addView(scroll(page), lp(-1, 0, 1));
        LinearLayout header = new LinearLayout(this);
        int accent = mediaAccent(m);
        header.setPadding(dp(16), dp(20), dp(16), dp(20));
        android.graphics.drawable.GradientDrawable hero = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{tint(SURFACE, accent, .10f), SURFACE});
        hero.setCornerRadius(dp(20));
        hero.setStroke(dp(1), tint(BORDER, accent, .15f));
        header.setBackground(hero);
        View detailCover = coverView(m.coverImage, m.title, 80, 114, m.coverPositionX, m.coverPositionY, m.coverZoom);
        if (m.coverImage != null && !m.coverImage.isEmpty()) {
            detailCover.setContentDescription("Preview cover for " + m.title);
            detailCover.setOnClickListener(v -> showFullCover(m));
            detailCover.setFocusable(true);
        }
        header.addView(detailCover, lp(dp(80), dp(114)));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView name = title(m.title);
        name.setTextSize(24);
        name.setLineSpacing(dp(2), 1.05f);
        name.setMinHeight(dp(48));
        name.setContentDescription(m.title + ". Tap to copy title; hold to edit media.");
        name.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Media title", m.title));
        });
        name.setOnLongClickListener(v -> { showEditor(id); return true; });
        androidx.core.view.ViewCompat.replaceAccessibilityAction(name,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                "Copy title", (view, arguments) -> { name.performClick(); return true; });
        androidx.core.view.ViewCompat.replaceAccessibilityAction(name,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                "Edit media", (view, arguments) -> { showEditor(id); return true; });
        add(identity, name, 0, 8);
        add(identity, muted(typeName(m.type) + " · " + cap(m.releaseStatus)), 0, 8);
        LinearLayout actions = new LinearLayout(this);
        TextView favorite = action(m.isFavorite ? "★" : "☆", v -> write(() -> viewModel.repository.favorite(id), () -> showDetail(id)));
        favorite.setContentDescription(m.isFavorite ? "Unfavorite" : "Favorite");
        favorite.setTextColor(accent);
        actions.addView(favorite, lp(dp(48), dp(48)));
        TextView more = action("⋮", v -> mediaMenu(v, m));
        more.setContentDescription("Media actions");
        actions.addView(more, lp(dp(48), dp(48)));
        margin(more, 8, 0, 0, 0);
        ((LinearLayout) root.getChildAt(0)).addView(actions, lp(-2, -2));
        header.addView(identity, lp(0, -2, 1));
        margin(identity, 12, 0, 0, 0);
        add(page, header, 0, 12);
        com.google.android.material.chip.ChipGroup tags = new com.google.android.material.chip.ChipGroup(this);
        for (GenreEntity g : viewModel.repository.genresFor(id)) {
            // Preserve the compact tag while giving its navigation action a 48dp target.
            android.widget.FrameLayout target = new android.widget.FrameLayout(this);
            target.setMinimumHeight(dp(48));
            target.setMinimumWidth(dp(48));
            TextView tag = chip(g.name, color(g.color));
            tag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            target.addView(tag, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            target.setContentDescription("Show titles in " + g.name);
            target.setFocusable(true);
            target.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(withAlpha(color(g.color), 40)), null, bg(Color.WHITE, 8)));
            target.setOnClickListener(v -> {
                clearFilters();
                query = "";
                genreFilters.add(g.id);
                homeScrollY = 0;
                showHome();
            });
            tags.addView(target);
        }
        add(page, tags, 0, 24);
        LinearLayout progressHeading = new LinearLayout(this);
        progressHeading.setGravity(Gravity.CENTER_VERTICAL);
        progressHeading.addView(sectionTitle("Your progress"), lp(0, -2, 1));
        TextView history = detailTextAction("History", accent, v -> {
            if (progressUndoBar != null) progressUndoBar.dismiss();
            ProgressHistoryDialog.show(this, viewModel.repository, viewModel.executor, id, m.title, accent, this::undoProgress);
        });
        history.setContentDescription("Show progress history");
        progressHeading.addView(history, lp(-2, -2));
        add(page, progressHeading, 0, 8);
        add(page, progressControls(m, p), 0, 8);
        LinearLayout tracking = new LinearLayout(this);
        tracking.addView(action(statusLabel(p.trackingStatus, m.type), v -> editTracking(m, p)), lp(0, -2, 1));
        tracking.addView(action(p.rating == null ? "Add rating" : "★ " + p.rating + "/10", v -> editRating(m, p)), lp(0, -2, 1));
        margin(tracking.getChildAt(1), 8, 0, 0, 0);
        add(page, tracking, 0, 8);
        detailUpdatedLabel = muted("Updated " + date(p.lastUpdatedAt));
        add(page, detailUpdatedLabel, 0, 24);
        add(page, sectionTitle("Before you continue"), 0, 12);
        LinearLayout memory = new LinearLayout(this);
        memory.setOrientation(LinearLayout.VERTICAL);
        memory.setPadding(dp(16), dp(4), dp(8), dp(4));
        memory.setBackground(outlined(SURFACE, tint(BORDER, accent, .10f), 16));
        StoryMemoryEntity st = viewModel.repository.story(id);
        storyRow(memory, id, "Main character", st == null ? null : st.mainCharacterName, 0);
        storyRow(memory, id, "Story reminder", st == null ? null : st.storySummary, 1);
        storyRow(memory, id, "Where I left off", st == null ? null : st.lastStoryPoint, 2);
        storyRow(memory, id, "Important notes", st == null ? null : st.importantNotes, 3);
        add(page, memory, 0, 24);
        add(page, sectionTitle("Characters"), 0, 12);
        for (CharacterEntity c : viewModel.repository.characters(id)) addCharacterRow(page, c, id);
        add(page, action("+ Add character", v -> showCharacterDialog(id, null)), 0, 24);
        add(page, sectionTitle("Personal notes"), 0, 8);
        if (p.notes != null && !p.notes.isEmpty()) {
            TextView notes = label(p.notes, 16, TEXT);
            notes.setLineSpacing(dp(3), 1.08f);
            notes.setPadding(dp(16), dp(16), dp(16), dp(16));
            notes.setBackground(bg(SURFACE, 14));
            add(page, notes, 0, 4);
        }
        TextView notesAction = detailTextAction(p.notes == null || p.notes.isEmpty() ? "+ Add personal notes" : "Edit personal notes", accent, v ->
                editTextDialog("Personal notes", p.notes, true, (value, saved, failed) ->
                        write(() -> viewModel.repository.updateTracking(id, p.trackingStatus, p.rating, value), () -> {
                            saved.run(); showDetail(id);
                        }, failed)));
        page.addView(notesAction, lp(-2, -2));
        margin(notesAction, 0, 0, 0, 24);
        add(page, action("Edit media", v -> showEditor(id)), 0, 24);
        TextView delete = action("Delete media", v -> confirmDelete(m));
        delete.setTextColor(DANGER);
        add(page, delete, 0, 0);
        install(root);
    }

    private TextView sectionTitle(String s) {
        TextView v = label(s, 20, TEXT);
        MediaEntity media = screen.equals("detail") ? viewModel.repository.media(detailId) : null;
        if (media != null) {
            v.setTextSize(18);
            android.graphics.drawable.GradientDrawable mark = bg(mediaAccent(media), 2);
            mark.setBounds(0, 0, dp(3), dp(16));
            v.setCompoundDrawablesRelative(mark, null, null, null);
            v.setCompoundDrawablePadding(dp(10));
            androidx.core.view.ViewCompat.setAccessibilityHeading(v, true);
        }
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextInputEditText memoryField(LinearLayout p, String hint, String value) {
        return field(p, hint, value, true);
    }

    private void addCharacterRow(LinearLayout parent, CharacterEntity c, long id) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(16));
        row.setBackground(bg(SURFACE, 14));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (c.image != null && !c.image.isEmpty()) {
            View photo = coverView(c.image, c.name, 64, 88, .5f, .5f, 1f);
            photo.setContentDescription("Preview character image for " + c.name);
            photo.setOnClickListener(v -> showFullImage(c.name, c.image, true));
            top.addView(photo, lp(dp(64), dp(88)));
            margin(photo, 0, 0, 12, 0);
        }
        TextView name = label(c.name, 16, TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(name, lp(0, -2, 1));
        TextView characterMenu = action("⋮", v -> {
            PopupMenu menu = new PopupMenu(this, v);
            menu.getMenu().add("Edit");
            menu.getMenu().add("Delete");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getTitle().toString().equals("Edit")) showCharacterDialog(id, c);
                else new MaterialAlertDialogBuilder(this).setTitle("Delete " + c.name + "?")
                        .setMessage("Only this character reminder will be removed.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) ->
                                write(() -> viewModel.repository.deleteCharacter(c.id), () -> showDetail(id))).show();
                return true;
            });
            menu.show();
        });
        characterMenu.setContentDescription("Character options for " + c.name);
        top.addView(characterMenu, lp(dp(48), dp(48)));
        row.addView(top);
        if (c.role != null && !c.role.isEmpty()) add(row, muted(c.role), 0, 4);
        if (c.description != null && !c.description.isEmpty()) {
            TextView description = label(c.description, 15, TEXT);
            description.setLineSpacing(dp(3), 1.08f);
            add(row, description, 4, 0);
        }
        add(parent, row, 0, 12);
    }

    private void showCharacterDialog(long id, CharacterEntity existing) {
        int panelColor = Color.rgb(30, 37, 32);
        int accent = mediaAccent(viewModel.repository.media(id));
        Bundle restored = characterDraft;
        characterDraft = new Bundle();
        characterDraft.putLong("mediaId", id);
        characterDraft.putLong("id", existing == null ? 0 : existing.id);
        LinearLayout form = dialogForm();
        form.setPadding(dp(24), dp(16), dp(24), dp(8));
        form.setBackgroundColor(panelColor);
        form.setContentDescription("Character editor form");
        LinearLayout photoRow = new LinearLayout(this);
        photoRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.FrameLayout photoFrame = new android.widget.FrameLayout(this);
        photoRow.addView(photoFrame, lp(dp(64), dp(88)));
        margin(photoFrame, 0, 0, 12, 0);
        LinearLayout photoActions = new LinearLayout(this);
        photoActions.setOrientation(LinearLayout.VERTICAL);
        TextView choose = action("Add character image", v -> {
            captureCharacterDraft();
            characterImagePicker.launch(new String[]{"image/*"});
        });
        choose.setTextSize(14);
        choose.setTextColor(accent);
        choose.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(tint(panelColor, accent, .22f)),
                outlined(Color.rgb(40, 49, 43), tint(BORDER, accent, .28f), 12), null));
        add(photoActions, choose, 0, 8);
        TextInputEditText imageSource = new TextInputEditText(this);
        imageSource.setText(existing == null ? "" : existing.image);
        TextView remove = detailTextAction("Remove image", DANGER, v -> imageSource.setText(""));
        photoActions.addView(remove, lp(-1, -2));
        photoRow.addView(photoActions, lp(0, -2, 1));
        add(form, photoRow, 0, 16);
        TextInputEditText name = characterField(form, "Name", existing == null ? "" : existing.name, false, accent);
        TextInputEditText role = characterField(form, "Role", existing == null ? "" : existing.role, false, accent);
        TextInputEditText description = characterField(form, "Description", existing == null ? "" : existing.description, true, accent);
        characterFields = new TextInputEditText[]{name, role, description, imageSource};
        Runnable updatePhoto = () -> {
            String source = text(imageSource);
            photoFrame.removeAllViews();
            View photo = coverView(source, "Character image", 64, 88, .5f, .5f, 1f);
            if (source.isEmpty()) {
                android.widget.ImageView placeholder = new androidx.appcompat.widget.AppCompatImageView(this);
                placeholder.setImageResource(R.drawable.ic_character_placeholder);
                placeholder.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                photo = placeholder;
            }
            photo.setBackground(outlined(Color.rgb(40, 49, 43), Color.rgb(76, 91, 80), 12));
            photo.setClipToOutline(true);
            photo.setContentDescription(source.isEmpty() ? "No character image" : "Preview selected character image");
            if (!source.isEmpty()) photo.setOnClickListener(v -> showFullImage(text(name), source, true));
            photoFrame.addView(photo, new android.widget.FrameLayout.LayoutParams(-1, -1));
            choose.setText(source.isEmpty() ? "Add character image" : "Change character image");
            remove.setVisibility(source.isEmpty() ? View.GONE : View.VISIBLE);
            margin(choose, 0, 0, 0, source.isEmpty() ? 0 : 4);
        };
        imageSource.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { updatePhoto.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(24), dp(24), 0);
        TextView heading = label(existing == null ? "Add character" : "Edit character", 22, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        add(header, heading, 0, 6);
        TextView hint = label("A face and a few details to remember.", 13, Color.rgb(188, 199, 191));
        add(header, hint, 0, 0);
        ScrollView formScroll = scroll(form);
        formScroll.setFillViewport(false);
        formScroll.setBackgroundColor(Color.TRANSPARENT);
        formScroll.setPadding(dp(1), 0, dp(1), 0);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setBackground(outlined(panelColor, tint(BORDER, accent, .18f), 24))
                .setCustomTitle(header).setView(formScroll)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        boolean[] saving = {false};
        Runnable save = () -> {
            if (saving[0]) return;
            if (text(name).isEmpty()) {
                name.setError("Name is required");
                name.requestFocus();
                return;
            }
            CharacterEntity c = new CharacterEntity();
            c.id = existing == null ? 0 : existing.id;
            c.mediaId = id;
            c.name = text(name);
            c.role = text(role);
            c.description = text(description);
            c.image = text(imageSource).isEmpty() ? null : text(imageSource);
            saving[0] = true;
            dialog.getButton(-1).setEnabled(false);
            choose.setEnabled(false); remove.setEnabled(false);
            Runnable failed = () -> {
                saving[0] = false; dialog.getButton(-1).setEnabled(true);
                choose.setEnabled(true); remove.setEnabled(true);
            };
            CoverStore.get(this).ensure(c.image, file -> {
                if (isDestroyed() || isFinishing()) return;
                if (c.image != null && file == null) {
                    failed.run();
                    toast("Image could not be saved. Choose another image or remove it and try again.");
                    return;
                }
                write(() -> viewModel.repository.saveCharacter(c), () -> {
                    dialog.dismiss();
                    showDetail(id);
                }, failed);
            });
        };
        protectNoteDraft(dialog, Arrays.asList(characterFields), save, () -> saving[0]);
        if (restored != null) {
            String[] keys = {"name", "role", "description", "image"};
            for (int i = 0; i < keys.length; i++) characterFields[i].setText(restored.getString(keys[i], ""));
        }
        updatePhoto.run();
        characterDialog = dialog;
        dialog.setOnDismissListener(d -> {
            // Outside-tap cancellation re-shows the editor beneath Keep / Discard.
            if (!dialog.isShowing() && characterDialog == dialog) {
                characterDraft = null; characterFields = null; characterDialog = null;
            }
        });
        dialog.show();
        android.widget.Button saveButton = dialog.getButton(-1), cancelButton = dialog.getButton(-2);
        saveButton.setTextColor(readable(accent));
        saveButton.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(tint(accent, Color.WHITE, .2f)), bg(accent, 12), null));
        androidx.core.view.ViewCompat.setBackgroundTintList(saveButton, android.content.res.ColorStateList.valueOf(accent));
        saveButton.setMinHeight(dp(48)); saveButton.setMinimumWidth(dp(88));
        saveButton.setPadding(dp(20), dp(8), dp(20), dp(8));
        margin(saveButton, 12, 0, 0, 0);
        cancelButton.setTextColor(TEXT);
        cancelButton.setMinHeight(dp(48));
    }

    private TextInputEditText characterField(LinearLayout form, String hint, String value, boolean multi, int accent) {
        TextInputLayout box = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        box.setHint(hint);
        box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        box.setBoxCornerRadii(dp(12), dp(12), dp(12), dp(12));
        box.setBoxBackgroundColor(Color.rgb(40, 49, 43));
        box.setBoxStrokeColorStateList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{accent, Color.rgb(99, 115, 104)}));
        box.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(Color.rgb(199, 209, 201)));
        box.setHintTextColor(android.content.res.ColorStateList.valueOf(accent));
        TextInputEditText input = new TextInputEditText(box.getContext());
        // Remove the Activity theme's native underline so the Material outline is actually drawn.
        input.setBackground(null);
        input.setText(value == null ? "" : value);
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setPadding(dp(14), dp(16), dp(14), dp(16));
        input.setSingleLine(!multi);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        if (multi) { input.setMinLines(3); input.setGravity(Gravity.TOP | Gravity.START); }
        input.setMinHeight(dp(multi ? 108 : 56));
        box.addView(input, lp(-1, -2));
        add(form, box, 0, 16);
        return input;
    }

    private void captureCharacterDraft() {
        if (characterDraft == null || characterFields == null) return;
        String[] keys = {"name", "role", "description", "image"};
        for (int i = 0; i < keys.length; i++)
            characterDraft.putString(keys[i], characterFields[i].getText() == null ? "" : characterFields[i].getText().toString());
    }

    private void showProgressDialog(MediaEntity m, UserProgressEntity p) {
        LinearLayout form = dialogForm();
        TextInputEditText input = field(form, unit(m), number(p.currentProgress), false);
        add(form, muted("Manual changes are recorded as corrections."), 0, 4);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.selectAll();
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Update progress")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Update", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                double value = parseDouble(text(input), 0);
                dialog.dismiss();
                changeProgress(m.id, value);
            } catch (IllegalArgumentException error) {
                input.setError(error.getMessage());
                input.requestFocus();
            }
        }));
        dialog.show();
    }

    private void showFilterDialog() {
        Set<String> types = new HashSet<>(typeFilters), statuses = new HashSet<>(statusFilters);
        Set<Long> genres = new HashSet<>(genreFilters);
        boolean[] favorites = {favoritesOnly};
        LinearLayout form = dialogForm();
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        add(form, sectionTitle("Filter media"), 0, 12);
        add(form, muted("Media type"), 0, 4);
        com.google.android.material.chip.ChipGroup typeGroup = new com.google.android.material.chip.ChipGroup(this);
        TextView apply = accentAction("Apply filters", v -> {
            typeFilters.clear();
            typeFilters.addAll(types);
            statusFilters.clear();
            statusFilters.addAll(statuses);
            genreFilters.clear();
            genreFilters.addAll(genres);
            favoritesOnly = favorites[0];
            homeScrollY = 0;
            sheet.dismiss();
            loadHome();
        });
        Runnable count = () -> apply.setText("Show " + filteredMedia(types, statuses, genres, favorites[0]).size() + " titles");
        for (String t : typeKeys())
            selectionChip(typeGroup, typeName(t), types.contains(t), selected -> {
                if (selected) types.add(t);
                else types.remove(t);
                count.run();
            });
        form.addView(typeGroup);
        add(form, muted("Tracking status"), 12, 4);
        com.google.android.material.chip.ChipGroup statusGroup = new com.google.android.material.chip.ChipGroup(this);
        for (String status : new String[]{"plan_to_read", "reading", "completed", "dropped"})
            selectionChip(statusGroup, statusLabel(status, null), statuses.contains(status), selected -> {
                if (selected) statuses.add(status);
                else statuses.remove(status);
                count.run();
            });
        form.addView(statusGroup);
        add(form, muted("Genres · Match any selected"), 12, 4);
        com.google.android.material.chip.ChipGroup genreGroup = new com.google.android.material.chip.ChipGroup(this);
        for (GenreEntity g : viewModel.repository.genres())
            selectionChip(genreGroup, g.name, genres.contains(g.id), selected -> {
                if (selected) genres.add(g.id);
                else genres.remove(g.id);
                count.run();
            });
        form.addView(genreGroup);
        android.widget.CheckBox favorite = new android.widget.CheckBox(this);
        favorite.setText("Favorites only");
        favorite.setTextColor(TEXT);
        favorite.setChecked(favorites[0]);
        favorite.setMinHeight(dp(48));
        favorite.setOnCheckedChangeListener((b, checked) -> {
            favorites[0] = checked;
            count.run();
        });
        form.addView(favorite);
        add(form, action("Reset", v -> {
            for (com.google.android.material.chip.ChipGroup group : Arrays.asList(typeGroup, statusGroup, genreGroup))
                for (int i = 0; i < group.getChildCount(); i++)
                    ((com.google.android.material.chip.Chip) group.getChildAt(i)).setChecked(false);
            favorite.setChecked(false);
            types.clear();
            statuses.clear();
            genres.clear();
            count.run();
        }), 16, 8);
        add(form, apply, 0, 8);
        add(form, action("Cancel", v -> sheet.dismiss()), 0, 0);
        count.run();
        sheet.setContentView(scroll(form));
        sheet.show();
    }

    private String findGenreName(List<GenreEntity> gs, long id) {
        for (GenreEntity g : gs) if (g.id == id) return g.name;
        return "all genres";
    }

    private void showSortDialog() {
        LinearLayout form = dialogForm();
        add(form, sectionTitle("Sort media"), 0, 16);
        String[] values = {"updated", "rating", "title"};
        Spinner sort = spinner(form, new String[]{"Last updated", "Rating", "Title"}, selectedSort.equals("updated") ? "Last updated" : selectedSort.equals("rating") ? "Rating" : "Title");
        Spinner direction = spinner(form, new String[]{"Default direction", "Reverse direction"}, reverseSort ? "Reverse direction" : "Default direction");
        add(form, muted("Default: newest updated, highest rating, title A–Z. Reverse: oldest updated, lowest rating, title Z–A. Unrated titles always appear last."), 0, 12);
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        add(form, accentAction("Apply sort", v -> {
            selectedSort = values[sort.getSelectedItemPosition()];
            reverseSort = direction.getSelectedItemPosition() == 1;
            sheet.dismiss();
            loadHome();
        }), 0, 8);
        add(form, action("Cancel", v -> sheet.dismiss()), 0, 0);
        sheet.setContentView(scroll(form));
        sheet.show();
    }

    private void showStats() {
        screen = "stats";
        LinearLayout root = shell("Statistics", "", false, true);
        LinearLayout page = dialogForm();
        content.addView(scroll(page), lp(-1, 0, 1));
        List<MediaEntity> media = viewModel.repository.media();
        Double average = viewModel.repository.averageRating();
        int rated = 0;
        for (MediaEntity m : media) if (viewModel.repository.progress(m.id).rating != null) rated++;
        addStat(page, "Total media", String.valueOf(media.size()), "Your collection");
        for (String status : new String[]{"reading", "completed", "dropped", "plan_to_read"}) {
            TextView row = action(statusLabel(status, null) + "   " + viewModel.repository.countStatus(status), v -> {
                clearFilters();
                statusFilters.add(status);
                showHome();
            });
            add(page, row, 0, 8);
        }
        addStat(page, "Favorites", String.valueOf(viewModel.repository.favoriteCount()), "Saved favorites");
        addStat(page, "Average rating", average == null ? "—" : String.format(Locale.US, "%.1f / 10", average), rated + " rated titles");
        add(page, sectionTitle("Recently updated"), 24, 12);
        media.sort((a, b) -> Long.compare(viewModel.repository.progress(b.id).lastUpdatedAt, viewModel.repository.progress(a.id).lastUpdatedAt));
        for (int i = 0; i < Math.min(5, media.size()); i++) {
            MediaEntity m = media.get(i);
            UserProgressEntity p = viewModel.repository.progress(m.id);
            LinearLayout row = new LinearLayout(this);
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(m.title, 16, TEXT);
            name.setMinHeight(dp(48));
            name.setOnClickListener(v -> openDetail(m.id));
            add(text, name, 0, 4);
            add(text, muted(formatProgress(m, p) + " · " + date(p.lastUpdatedAt)), 0, 12);
            row.addView(text, lp(0, -2, 1));
            TextView more = action("⋮", v -> mediaMenu(v, m));
            more.setContentDescription("More actions for " + m.title);
            row.addView(more, lp(dp(48), dp(48)));
            add(page, row, 0, 8);
        }
        if (media.isEmpty()) add(page, muted("Your recent titles will appear here."), 0, 0);
        install(root);
    }

    private void addStat(LinearLayout p, String heading, String value, String sub) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(bg(SURFACE, 12));
        add(card, muted(heading), 0, 4);
        TextView number = label(value, 28, TEXT);
        number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(card, number, 0, 4);
        add(card, muted(sub), 0, 0);
        add(p, card, 0, 12);
    }

    private void showMediaTypes() {
        screen = "types";
        LinearLayout root = shell("Media types", "", false, true);
        LinearLayout page = dialogForm();
        content.addView(scroll(page), lp(-1, 0, 1));
        add(page, muted("Organize your collection your way. Each type tracks chapters or episodes."), 0, 20);
        java.util.Map<String, Integer> usage = new java.util.HashMap<>();
        for (MediaEntity media : viewModel.repository.media())
            usage.put(media.type, usage.getOrDefault(media.type, 0) + 1);
        for (MediaTypeEntity type : viewModel.repository.mediaTypes()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(12), dp(12));
            row.setBackground(outlined(SURFACE, BORDER, 14));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(type.name, 17, TEXT);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            info.addView(name);
            int count = usage.getOrDefault(type.key, 0);
            add(info, muted((type.usesEpisodes ? "Episodes" : "Chapters") + " · " + count + (count == 1 ? " title" : " titles")), 6, 0);
            info.setMinimumHeight(dp(48));
            info.setOnClickListener(v -> showMediaTypeDialog(type));
            info.setContentDescription("Edit media type " + type.name);
            row.addView(info, lp(0, -2, 1));
            TextView more = action("⋮", v -> {
                PopupMenu menu = new PopupMenu(this, v);
                menu.getMenu().add("Edit media type");
                menu.getMenu().add("Delete media type");
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getTitle().toString().equals("Edit media type")) showMediaTypeDialog(type);
                    else confirmDeleteMediaType(type, count);
                    return true;
                });
                menu.show();
            });
            more.setContentDescription("Actions for media type " + type.name);
            row.addView(more, lp(dp(48), dp(48)));
            margin(more, 10, 0, 0, 0);
            add(page, row, 0, 12);
        }
        add(page, accentAction("+ New media type", v -> showMediaTypeDialog(null)), 8, 0);
        install(root);
    }

    private void showMediaTypeDialog(MediaTypeEntity existing) {
        LinearLayout form = dialogForm();
        TextInputEditText name = field(form, "Media type name", existing == null ? "" : existing.name, false);
        add(form, muted("Progress unit"), 0, 8);
        Spinner unit = spinner(form, new String[]{"chapters", "episodes"}, existing != null && existing.usesEpisodes ? "episodes" : "chapters");
        unit.setContentDescription("Progress unit");
        add(form, muted("Changing the unit keeps progress numbers and reading history unchanged."), 0, 4);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "New media type" : "Edit media type")
                .setView(scroll(form)).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String value = text(name);
            if (value.isEmpty()) { name.setError("Media type name is required"); return; }
            for (MediaTypeEntity type : viewModel.repository.mediaTypes()) {
                if (type.name.equalsIgnoreCase(value) && (existing == null || !type.key.equals(existing.key))) {
                    name.setError("Media type names must be unique"); return;
                }
            }
            MediaTypeEntity type = new MediaTypeEntity();
            type.key = existing == null ? "" : existing.key;
            type.name = value;
            type.usesEpisodes = unit.getSelectedItemPosition() == 1;
            dialog.getButton(-1).setEnabled(false);
            write(() -> viewModel.repository.saveMediaType(type), () -> {
                dialog.dismiss();
                if (screen.equals("editor")) {
                    formDraft = captureDraft();
                    formDraft.putString("type", type.key);
                    showEditor(editingId);
                } else showMediaTypes();
            });
        }));
        dialog.show();
    }

    private void confirmDeleteMediaType(MediaTypeEntity type, int count) {
        List<MediaTypeEntity> replacements = viewModel.repository.mediaTypes();
        replacements.removeIf(t -> t.key.equals(type.key));
        if (replacements.isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle("Keep one media type")
                    .setMessage("Add another type before deleting this one.").setPositiveButton("OK", null).show();
            return;
        }
        LinearLayout form = dialogForm();
        add(form, label(count == 0 ? "No titles use this type. Your collection will be kept."
                : "Move " + count + " titles to another type. Progress, covers, genres, and notes will be kept.", 14, TEXT), 0, 12);
        String[] keys = new String[replacements.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = replacements.get(i).key;
        Spinner replacement = count == 0 ? null : spinner(form, keys, keys[0]);
        new MaterialAlertDialogBuilder(this).setTitle("Delete “" + type.name + "”?").setView(scroll(form))
                .setNegativeButton("Cancel", null).setPositiveButton(count == 0 ? "Delete" : "Move & delete", (d, w) -> {
                    String key = replacement == null ? null : replacement.getSelectedItem().toString();
                    write(() -> viewModel.repository.deleteMediaType(type.key, key), () -> {
                        if (typeFilters.remove(type.key) && key != null) typeFilters.add(key);
                        showMediaTypes();
                    });
                }).show();
    }

    private void showGenres() {
        screen = "genres";
        LinearLayout root = shell("Genres", "", false, true);
        LinearLayout page = dialogForm();
        content.addView(scroll(page), lp(-1, 0, 1));
        if (viewModel.repository.genres().isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(16), dp(48), dp(16), dp(16));
            TextView emptyIcon = label("🏷️", 36, TEXT);
            emptyIcon.setGravity(Gravity.CENTER);
            add(empty, emptyIcon, 0, 12);
            TextView emptyTitle = title("No genres yet");
            emptyTitle.setGravity(Gravity.CENTER);
            add(empty, emptyTitle, 0, 8);
            TextView emptyDesc = muted("Create genres to organize your titles\nwith beautiful colored tags.");
            emptyDesc.setGravity(Gravity.CENTER);
            add(empty, emptyDesc, 0, 24);
            add(empty, accentAction("+ New genre", v -> showGenreDialog(null)), 0, 0);
            page.addView(empty);
        } else {
            for (GenreEntity g : viewModel.repository.genres()) {
                int c = color(g.color);
                LinearLayout card = new LinearLayout(this);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setBackground(bg(SURFACE, 12));
                card.setPadding(0, 0, dp(4), 0);
                card.setMinimumHeight(dp(64));
                // Left color accent bar
                View bar = new View(this);
                bar.setBackground(bg(c, 12));
                LinearLayout.LayoutParams barLp = lp(dp(5), dp(56));
                barLp.setMargins(dp(2), 0, 0, 0);
                card.addView(bar, barLp);
                // Genre info
                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(dp(14), dp(10), dp(8), dp(10));
                TextView genreChip = chip(g.name, c);
                genreChip.setTextSize(14);
                genreChip.setPadding(dp(14), dp(6), dp(14), dp(6));
                genreChip.setOnClickListener(v -> showGenreDialog(g));
                info.addView(genreChip, lp(-2, -2));
                int usage = genreUsage(g.id);
                TextView usageLabel = muted(usage + " " + (usage == 1 ? "title" : "titles"));
                usageLabel.setPadding(dp(4), dp(4), 0, 0);
                info.addView(usageLabel);
                card.addView(info, lp(0, -2, 1));
                // Overflow button
                TextView menuBtn = action("⋮", v -> {
                    PopupMenu menu = new PopupMenu(this, v);
                    menu.getMenu().add("Edit genre");
                    menu.getMenu().add("Delete genre");
                    menu.setOnMenuItemClickListener(item -> {
                        if (item.getTitle().toString().equals("Delete genre"))
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Delete " + g.name + "?").setMessage("Remove this genre from " + genreUsage(g.id) + " titles. Your media will be kept.")
                                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> write(() -> viewModel.repository.deleteGenre(g.id), () -> {
                                        genreFilters.remove(g.id);
                                        showGenres();
                                    })).show();
                        else showGenreDialog(g);
                        return true;
                    });
                    menu.show();
                });
                menuBtn.setTextSize(20);
                card.addView(menuBtn, lp(dp(44), dp(44)));
                add(page, card, 0, 10);
            }
            add(page, accentAction("+ New genre", v -> showGenreDialog(null)), 16, 0);
        }
        install(root);
    }

    private void showGenreDialog(GenreEntity existing) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(16), dp(20), dp(24));
        String initialColor = existing == null ? "#9CBFFF" : existing.color;
        int[] currentColor = {color(initialColor)};
        float[] hsv = new float[3];
        Color.colorToHSV(currentColor[0], hsv);
        boolean[] updatingFromSlider = {false};

        // ---- Title ----
        add(form, sectionTitle(existing == null ? "New genre" : "Edit genre"), 0, 16);

        // ---- Live preview strip ----
        LinearLayout previewStrip = new LinearLayout(this);
        previewStrip.setGravity(Gravity.CENTER);
        previewStrip.setPadding(dp(16), dp(14), dp(16), dp(14));
        previewStrip.setBackground(bg(currentColor[0], 12));
        TextView previewLabel = label(existing == null ? "Genre Preview" : "Preview", 16, readable(currentColor[0]));
        previewLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewLabel.setGravity(Gravity.CENTER);
        previewStrip.addView(previewLabel, lp(-1, -2));
        add(form, previewStrip, 0, 16);

        // ---- Name field ----
        TextInputEditText name = field(form, "Genre name", existing == null ? "" : existing.name, false);

        // ---- Color hex field ----
        TextInputEditText colorInput = field(form, "Color code", initialColor, false);

        // ---- Color picker section ----
        LinearLayout pickerSection = new LinearLayout(this);
        pickerSection.setOrientation(LinearLayout.VERTICAL);

        // Hue bar label
        add(pickerSection, muted("Hue"), 8, 4);

        // Hue SeekBar (0-360)
        android.widget.SeekBar hueBar = new android.widget.SeekBar(this);
        hueBar.setMax(360);
        hueBar.setProgress((int) hsv[0]);
        hueBar.setMinimumHeight(dp(48));
        hueBar.setContentDescription("Hue color slider");
        // Build hue rainbow gradient
        int[] hueColors = new int[7];
        for (int i = 0; i < 7; i++) {
            float[] h = {i * 60f, 1f, 1f};
            hueColors[i] = Color.HSVToColor(h);
        }
        android.graphics.drawable.GradientDrawable hueGrad = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, hueColors);
        hueGrad.setCornerRadius(dp(8));
        hueBar.setProgressDrawable(hueGrad);
        pickerSection.addView(hueBar);

        // Saturation bar label
        add(pickerSection, muted("Saturation"), 8, 4);

        // Saturation SeekBar (0-100)
        android.widget.SeekBar satBar = new android.widget.SeekBar(this);
        satBar.setMax(100);
        satBar.setProgress((int) (hsv[1] * 100));
        satBar.setMinimumHeight(dp(48));
        satBar.setContentDescription("Saturation slider");
        pickerSection.addView(satBar);

        // Brightness bar label
        add(pickerSection, muted("Brightness"), 8, 4);

        // Brightness SeekBar (0-100)
        android.widget.SeekBar valBar = new android.widget.SeekBar(this);
        valBar.setMax(100);
        valBar.setProgress((int) (hsv[2] * 100));
        valBar.setMinimumHeight(dp(48));
        valBar.setContentDescription("Brightness slider");
        pickerSection.addView(valBar);

        // Runnable to update saturation/brightness gradients
        Runnable updateBarGradients = () -> {
            float[] sGradStart = {hsv[0], 0f, hsv[2]};
            float[] sGradEnd = {hsv[0], 1f, hsv[2]};
            android.graphics.drawable.GradientDrawable satGrad = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.HSVToColor(sGradStart), Color.HSVToColor(sGradEnd)});
            satGrad.setCornerRadius(dp(8));
            satBar.setProgressDrawable(satGrad);
            satBar.setProgress(satBar.getProgress());
            float[] vGradStart = {hsv[0], hsv[1], 0f};
            float[] vGradEnd = {hsv[0], hsv[1], 1f};
            android.graphics.drawable.GradientDrawable valGrad = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.HSVToColor(vGradStart), Color.HSVToColor(vGradEnd)});
            valGrad.setCornerRadius(dp(8));
            valBar.setProgressDrawable(valGrad);
            valBar.setProgress(valBar.getProgress());
        };
        updateBarGradients.run();

        // Sync: sliders -> hex + preview
        Runnable syncFromSliders = () -> {
            hsv[0] = hueBar.getProgress();
            hsv[1] = satBar.getProgress() / 100f;
            hsv[2] = valBar.getProgress() / 100f;
            currentColor[0] = Color.HSVToColor(hsv);
            String hex = String.format(Locale.US, "#%06X", currentColor[0] & 0xFFFFFF);
            updatingFromSlider[0] = true;
            colorInput.setText(hex);
            colorInput.setSelection(hex.length());
            updatingFromSlider[0] = false;
            previewStrip.setBackground(bg(currentColor[0], 12));
            previewLabel.setTextColor(readable(currentColor[0]));
            updateBarGradients.run();
        };
        android.widget.SeekBar.OnSeekBarChangeListener sliderChange = new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }

            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) syncFromSliders.run();
            }
        };
        hueBar.setOnSeekBarChangeListener(sliderChange);
        satBar.setOnSeekBarChangeListener(sliderChange);
        valBar.setOnSeekBarChangeListener(sliderChange);

        // Toggle expand/collapse
        boolean[] expanded = {true};
        pickerSection.setVisibility(View.VISIBLE);
        TextView toggleBtn = action("▲  Hide color mixer", v -> {
            expanded[0] = !expanded[0];
            pickerSection.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            ((TextView) v).setText(expanded[0] ? "▲  Hide color mixer" : "▼  Show color mixer");
        });
        add(form, toggleBtn, 4, 8);
        form.addView(pickerSection);

        // ---- Preset palette ----
        add(form, muted("Quick presets"), 12, 6);
        com.google.android.material.chip.ChipGroup palette = new com.google.android.material.chip.ChipGroup(this);
        for (String hex : new String[]{"#9CBFFF", "#EDC27D", "#A8D8AD", "#C3ACF1", "#F0A39B", "#9ED1DF", "#FFD966", "#FF8A80", "#80CBC4", "#CE93D8", "#BCAAA4", "#90A4AE"}) {
            int pc = color(hex);
            TextView swatch = new TextView(this);
            swatch.setGravity(Gravity.CENTER);
            swatch.setMinHeight(dp(40));
            swatch.setMinWidth(dp(40));
            swatch.setPadding(dp(10), dp(6), dp(10), dp(6));
            swatch.setBackground(bg(pc, 10));
            swatch.setText("●");
            swatch.setTextColor(readable(pc));
            swatch.setTextSize(12);
            swatch.setOnClickListener(v -> {
                currentColor[0] = pc;
                Color.colorToHSV(pc, hsv);
                hueBar.setProgress((int) hsv[0]);
                satBar.setProgress((int) (hsv[1] * 100));
                valBar.setProgress((int) (hsv[2] * 100));
                syncFromSliders.run();
            });
            palette.addView(swatch);
        }
        form.addView(palette);

        // Sync: hex field -> sliders + preview (when user types)
        colorInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updatingFromSlider[0]) return;
                try {
                    int typed = Color.parseColor(s.toString());
                    currentColor[0] = typed;
                    Color.colorToHSV(typed, hsv);
                    hueBar.setProgress((int) hsv[0]);
                    satBar.setProgress((int) (hsv[1] * 100));
                    valBar.setProgress((int) (hsv[2] * 100));
                    previewStrip.setBackground(bg(typed, 12));
                    previewLabel.setTextColor(readable(typed));
                    updateBarGradients.run();
                } catch (Exception ignored) {
                }
            }

            public void afterTextChanged(android.text.Editable e) {
            }
        });

        // ---- Buttons ----
        add(form, new View(this), 16, 0);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        TextView cancel = action("Cancel", v -> sheet.dismiss());
        cancel.setMinWidth(dp(100));
        buttons.addView(cancel, lp(-2, -2));
        margin(cancel, 0, 0, 8, 0);
        TextView save = accentAction("Save", v -> {
            String n = text(name), c = text(colorInput);
            if (n.isEmpty()) {
                name.setError("Genre name is required");
                name.requestFocus();
                return;
            }
            GenreEntity match = viewModel.repository.findGenre(n);
            if (match != null && (existing == null || match.id != existing.id)) {
                name.setError("Genre names must be unique");
                name.requestFocus();
                return;
            }
            if (!c.matches("#[0-9a-fA-F]{6}")) {
                colorInput.setError("Use a color such as #9CBFFF");
                colorInput.requestFocus();
                return;
            }
            GenreEntity g = new GenreEntity();
            g.id = existing == null ? 0 : existing.id;
            g.name = n;
            g.color = c;
            write(() -> {
                if (existing == null) g.id = viewModel.repository.addGenre(g);
                else viewModel.repository.updateGenre(g);
            }, () -> {
                sheet.dismiss();
                if (screen.equals("editor")) {
                    editorGenres.add(g.id);
                    renderEditorGenres();
                } else showGenres();
            });
        });
        save.setMinWidth(dp(100));
        buttons.addView(save, lp(-2, -2));
        add(form, buttons, 8, 0);

        sheet.setContentView(scroll(form));
        sheet.show();
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void exportData() {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        exportPicker.launch("watlis_backup_" + fmt.format(new java.util.Date()) + ".json");
    }

    private void importData() {
        importPicker.launch(new String[]{"*/*"});
    }

    private void install(LinearLayout root) {
        if (!screen.equals("editor")) cancelChapterImport();
        if (progressUndoBar != null) { progressUndoBar.dismiss(); progressUndoBar = null; }
        drawer = new androidx.drawerlayout.widget.DrawerLayout(this);
        drawer.setBackgroundColor(BG);
        drawer.addView(root, new androidx.drawerlayout.widget.DrawerLayout.LayoutParams(-1, -1));
        drawerPanel = new android.widget.FrameLayout(this);
        androidx.drawerlayout.widget.DrawerLayout.LayoutParams params = new androidx.drawerlayout.widget.DrawerLayout.LayoutParams(dp(280), -1);
        params.gravity = androidx.core.view.GravityCompat.START;
        drawer.addView(drawerPanel, params);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(drawer, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.ime());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            drawerPanel.setPadding(bars.left, bars.top, 0, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        drawerPanel.addView(scroll(bottomNav()));
        drawer.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View view) {
                drawerPanel.removeAllViews();
                drawerPanel.addView(scroll(bottomNav()));
            }
        });
        if (screen.equals("editor") || screen.equals("detail"))
            drawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        setContentView(drawer);
        androidx.core.view.ViewCompat.requestApplyInsets(drawer);
    }

    private void write(Runnable operation, Runnable success) {
        write(operation, success, null);
    }

    private void write(Runnable operation, Runnable success, Runnable failure) {
        viewModel.executor.execute(() -> {
            try {
                operation.run();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) success.run();
                });
            } catch (Exception error) {
                android.util.Log.e("Watlis", "Local operation failed", error);
                try {
                    viewModel.repository.refresh();
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (failure != null) failure.run();
                    if (screen.equals("editor") && editorSaveButton != null) {
                        editorSaving = false;
                        editorSaveButton.setEnabled(true);
                        editorSaveButton.setText(editingId == null ? "Add media" : "Save changes");
                    }
                    if (!screen.equals("editor")) refreshScreen();
                    new MaterialAlertDialogBuilder(this).setTitle("Could not save changes")
                            .setMessage("Your change was not saved. " + (error instanceof IllegalArgumentException ? error.getMessage() : "Please try again."))
                            .setNegativeButton("Close", null).setPositiveButton("Retry", (d, w) -> write(operation, success, failure)).show();
                });
            }
        });
    }

    private void refreshScreen() {
        if (screen.equals("detail")) showDetail(detailId);
        else if (screen.equals("stats")) showStats();
        else if (screen.equals("genres")) showGenres();
        else if (screen.equals("types")) showMediaTypes();
        else if (screen.equals("home")) loadHome();
    }

    private void openDetail(long id) {
        if (screen.equals("home") && homeScroll != null) homeScrollY = homeScroll.getScrollY();
        detailOrigin = screen;
        showDetail(id);
    }

    private void navigateBack() {
        if (screen.equals("editor") && editorSaving) { toast("Saving your cover…"); return; }
        if (screen.equals("editor")) {
            Runnable discard = () -> {
                formDraft = null;
                formBaseline = null;
                if (editorOrigin.equals("detail") && editingId != null) showDetail(editingId);
                else if (editorOrigin.equals("stats")) showStats();
                else showHome();
            };
            if (!draftKey(captureDraft()).equals(formBaseline))
                new MaterialAlertDialogBuilder(this).setTitle("Keep your changes?")
                        .setMessage("Keep saves this media form, including your notes. Discard removes only unsaved changes.")
                        .setNegativeButton("Discard", (d, w) -> discard.run())
                        .setPositiveButton("Keep", (d, w) -> saveEditor(editingId == null ? null : viewModel.repository.media(editingId))).show();
            else discard.run();
        } else if (screen.equals("detail") && detailOrigin.equals("stats")) showStats();
        else showHome();
    }

    private void clearFilters() {
        typeFilters.clear();
        statusFilters.clear();
        genreFilters.clear();
        favoritesOnly = false;
    }

    private List<MediaEntity> filteredMedia(Set<String> types, Set<String> statuses, Set<Long> genres, boolean favorites) {
        List<MediaEntity> result = new ArrayList<>();
        String search = query.trim().toLowerCase(Locale.ROOT);
        for (MediaEntity m : viewModel.repository.media()) {
            if (!m.title.toLowerCase(Locale.ROOT).contains(search)) continue;
            if (!types.isEmpty() && !types.contains(m.type)) continue;
            if (!statuses.isEmpty() && !statuses.contains(viewModel.repository.progress(m.id).trackingStatus))
                continue;
            if (favorites && !m.isFavorite) continue;
            if (!genres.isEmpty()) {
                boolean match = false;
                for (GenreEntity g : viewModel.repository.genresFor(m.id))
                    if (genres.contains(g.id)) {
                        match = true;
                        break;
                    }
                if (!match) continue;
            }
            result.add(m);
        }
        return result;
    }

    private void sortMedia(List<MediaEntity> media) {
        media.sort((a, b) -> {
            int value;
            if (selectedSort.equals("title")) value = a.title.compareToIgnoreCase(b.title);
            else if (selectedSort.equals("rating")) {
                Integer ar = viewModel.repository.progress(a.id).rating, br = viewModel.repository.progress(b.id).rating;
                if (ar == null && br != null) return 1;
                if (ar != null && br == null) return -1;
                value = ar == null ? 0 : Integer.compare(br, ar);
            } else
                value = Long.compare(viewModel.repository.progress(b.id).lastUpdatedAt, viewModel.repository.progress(a.id).lastUpdatedAt);
            if (reverseSort) value = -value;
            if (value == 0) value = a.title.compareToIgnoreCase(b.title);
            return value == 0 ? Long.compare(a.id, b.id) : value;
        });
    }

    private void filterChip(com.google.android.material.chip.ChipGroup group, String label, Runnable remove) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
        chip.setText(label);
        chip.setCloseIconVisible(true);
        chip.setEnsureMinTouchTargetSize(true);
        chip.setOnCloseIconClickListener(v -> remove.run());
        group.addView(chip);
    }

    private void selectionChip(com.google.android.material.chip.ChipGroup group, String label, boolean checked, java.util.function.Consumer<Boolean> change) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setEnsureMinTouchTargetSize(true);
        chip.setOnCheckedChangeListener((v, value) -> change.accept(value));
        group.addView(chip);
    }

    private String statusLabel(String status, String type) {
        if ("reading".equals(status))
            return type == null ? "Reading / watching" : usesEpisodes(type) ? "Watching" : "Reading";
        if ("plan_to_read".equals(status))
            return type == null ? "Planned" : usesEpisodes(type) ? "Plan to watch" : "Plan to read";
        return cap(status);
    }

    private String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private String date(long timestamp) {
        return timestamp <= 0 ? "Not yet" : java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(timestamp));
    }

    private int readable(int color) {
        return androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.179 ? Color.BLACK : Color.WHITE;
    }

    private LinearLayout progressControls(MediaEntity m, UserProgressEntity p) {
        LinearLayout group = new LinearLayout(this);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setBackground(bg(SURFACE, 10));
        group.setPadding(dp(4), dp(4), dp(4), dp(4));
        TextView minus = progressButton("−"), plus = progressButton("+"), value = action(formatProgress(m, p), v -> showProgressDialog(m, p));
        value.setContentDescription("Edit progress for " + m.title);
        double[] shown = {p.currentProgress};
        minus.setEnabled(shown[0] > 0);
        minus.setAlpha(shown[0] > 0 ? 1f : .35f);
        java.util.function.Consumer<Double> update = delta -> {
            shown[0] = Math.max(0, shown[0] + delta);
            value.setText(unit(m) + " " + number(shown[0]));
            value.announceForAccessibility(value.getText());
            minus.setEnabled(shown[0] > 0);
            minus.setAlpha(shown[0] > 0 ? 1f : .35f);
            ProgressHistoryEntity[] change = {null};
            write(() -> change[0] = viewModel.repository.incrementProgress(m.id, delta), () -> {
                // Keep the touched control in place while rapid taps are queued.
                UserProgressEntity latest = viewModel.repository.progress(m.id);
                p.currentProgress = latest.currentProgress;
                p.lastUpdatedAt = latest.lastUpdatedAt;
                if (screen.equals("detail") && detailId == m.id && detailUpdatedLabel != null)
                    detailUpdatedLabel.setText("Updated " + date(latest.lastUpdatedAt));
                offerProgressUndo(change[0]);
            });
        };
        minus.setOnClickListener(v -> update.accept(-1d));
        plus.setOnClickListener(v -> update.accept(1d));
        int accent = mediaAccent(m);
        minus.setTextColor(accent);
        plus.setBackground(bg(accent, 10));
        plus.setTextColor(readable(accent));
        group.addView(minus, lp(dp(48), dp(48)));
        group.addView(value, lp(0, -2, 1));
        group.addView(plus, lp(dp(48), dp(48)));
        margin(value, 8, 0, 8, 0);
        return group;
    }

    private PositionedCoverView coverView(String source, String title, int width, int height, float positionX, float positionY, float zoom) {
        PositionedCoverView image = new PositionedCoverView(this);
        image.setContentDescription("Cover for " + title);
        image.setCoverPosition(positionX, positionY);
        image.setCoverZoom(zoom);
        image.setBackground(bg(SURFACE_HIGH, 6));
        image.setClipToOutline(true);
        android.graphics.drawable.Drawable placeholder = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.cover_placeholder);
        image.setImageDrawable(placeholder);
        if (source != null && !source.trim().isEmpty()) {
            java.lang.ref.WeakReference<PositionedCoverView> reference = new java.lang.ref.WeakReference<>(image);
            CoverStore.get(this).ensure(source, file -> {
                PositionedCoverView target = reference.get();
                if (target == null || isDestroyed() || file == null) return;
                coil.request.ImageRequest request = new coil.request.ImageRequest.Builder(this).data(file)
                        .size(Math.min(768, Math.round(dp(width) * zoom)), Math.min(768, Math.round(dp(height) * zoom)))
                        .scale(coil.size.Scale.FIT).placeholder(placeholder).error(placeholder).target(target).build();
                coil.Coil.imageLoader(this).enqueue(request);
            });
        }
        return image;
    }

    private void applyPickedCover() {
        if (pendingPickedCover == null || editorCover == null || !screen.equals("editor")) return;
        editorCoverX = 0.5f;
        editorCoverY = 0.5f;
        editorCoverZoom = 1f;
        selectedImageUri = pendingPickedCover;
        pendingPickedCover = null;
        editorCover.setText(selectedImageUri);
        updateCoverPreview();
        showCoverPositionEditor();
    }

    private String positionSummary(float x, float y) {
        return "Position · " + Math.round(x * 100) + "% across · " + Math.round(y * 100) + "% down";
    }

    private void showCoverPositionEditor() {
        if (text(editorCover).isEmpty()) {
            editorCover.setError("Choose a device image or enter an image URL first");
            editorCover.requestFocus();
            return;
        }
        updateCoverPreview();
        float[] position = {editorCoverX, editorCoverY, editorCoverZoom};
        com.google.android.material.bottomsheet.BottomSheetDialog sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout form = dialogForm();
        add(form, sectionTitle("Cover position"), 0, 8);
        add(form, muted("Choose which part appears in your list. The full image stays unchanged."), 0, 16);
        PositionedCoverView preview = coverView(text(editorCover), "Position preview", 256, 368, position[0], position[1], position[2]);
        preview.setContentDescription("List crop preview");
        LinearLayout frame = new LinearLayout(this);
        frame.setGravity(Gravity.CENTER);
        frame.setPadding(dp(16), dp(16), dp(16), dp(16));
        frame.setBackground(bg(SURFACE_HIGH, 12));
        frame.addView(preview, lp(dp(100), dp(144)));
        add(form, frame, 0, 8);
        TextView summary = muted(positionSummary(position[0], position[1]));
        summary.setGravity(Gravity.CENTER);
        add(form, summary, 0, 16);
        TextView zoomLabel = muted(zoomSummary(position[2]));
        add(form, zoomLabel, 0, 4);
        android.widget.SeekBar zoom = new android.widget.SeekBar(this);
        zoom.setMax(200);
        zoom.setProgress(Math.round((position[2] - 1) * 100));
        zoom.setContentDescription("Cover zoom");
        zoom.setMinimumHeight(dp(48));
        add(form, zoom, 0, 12);
        add(form, muted("Horizontal · Left to right"), 0, 4);
        android.widget.SeekBar horizontal = new android.widget.SeekBar(this);
        horizontal.setMax(100);
        horizontal.setProgress(Math.round(position[0] * 100));
        horizontal.setContentDescription("Horizontal cover position");
        horizontal.setMinimumHeight(dp(48));
        add(form, horizontal, 0, 12);
        add(form, muted("Vertical · Top to bottom"), 0, 4);
        android.widget.SeekBar vertical = new android.widget.SeekBar(this);
        vertical.setMax(100);
        vertical.setProgress(Math.round(position[1] * 100));
        vertical.setContentDescription("Vertical cover position");
        vertical.setMinimumHeight(dp(48));
        add(form, vertical, 0, 16);
        android.widget.SeekBar.OnSeekBarChangeListener change = new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }

            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                position[0] = horizontal.getProgress() / 100f;
                position[1] = vertical.getProgress() / 100f;
                position[2] = 1 + zoom.getProgress() / 100f;
                preview.setCoverPosition(position[0], position[1]);
                preview.setCoverZoom(position[2]);
                summary.setText(positionSummary(position[0], position[1]));
                zoomLabel.setText(zoomSummary(position[2]));
            }
        };
        horizontal.setOnSeekBarChangeListener(change);
        vertical.setOnSeekBarChangeListener(change);
        zoom.setOnSeekBarChangeListener(change);
        add(form, action("Center image", v -> {
            horizontal.setProgress(50);
            vertical.setProgress(50);
            zoom.setProgress(0);
        }), 0, 12);
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(action("Cancel", v -> sheet.dismiss()), lp(0, -2, 1));
        TextView apply = accentAction("Use position", v -> {
            editorCoverX = position[0];
            editorCoverY = position[1];
            editorCoverZoom = position[2];
            updateCoverPreview();
            sheet.dismiss();
        });
        buttons.addView(apply, lp(0, -2, 1));
        margin(apply, 8, 0, 0, 0);
        add(form, buttons, 0, 0);
        sheet.setContentView(scroll(form));
        sheet.show();
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    private String zoomSummary(float zoom) { return "Zoom · " + Math.round(zoom * 100) + "%"; }

    private void showFullCover(MediaEntity media) {
        showFullImage(media.title, media.coverImage, false);
    }

    private void showFullImage(String imageTitle, String source, boolean character) {
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.Theme_Watlis);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView title = label(imageTitle, 18, TEXT);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, lp(0, -2, 1));
        TextView close = action("×", v -> dialog.dismiss());
        close.setTextSize(24);
        close.setContentDescription(character ? "Close character image preview" : "Close cover preview");
        bar.addView(close, lp(dp(48), dp(48)));
        margin(close, 12, 0, 0, 0);
        root.addView(bar);
        CoverPreviewView image = new CoverPreviewView(this);
        image.setContentDescription(character ? "Full character image" : "Full cover image");
        root.addView(image, lp(-1, 0, 1));
        TextView status = muted(character ? "Loading character image…" : "Loading cover…");
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(20), dp(8), dp(20), dp(8));
        add(root, status, 0, 0);
        LinearLayout controls = new LinearLayout(this);
        controls.setPadding(dp(20), dp(8), dp(20), dp(16));
        TextView fit = action("Fit image", v -> image.resetZoom()), zoom = action("Zoom in", v -> image.zoomIn());
        controls.addView(fit, lp(0, -2, 1));
        controls.addView(zoom, lp(0, -2, 1));
        margin(zoom, 8, 0, 0, 0);
        root.addView(controls);
        fit.setEnabled(false);
        zoom.setEnabled(false);
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        // Request a screen-sized decode only when opened; never decode an unbounded original.
        int edge = Math.min(2560, Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) * 2);
        coil.request.Disposable[] loading = new coil.request.Disposable[2];
        boolean[] closed = {false};
        coil.request.ImageRequest request = new coil.request.ImageRequest.Builder(this).data(source)
                .size(edge, edge).scale(coil.size.Scale.FIT).memoryCachePolicy(coil.request.CachePolicy.DISABLED).target(new coil.target.Target() {
                    @Override
                    public void onStart(android.graphics.drawable.Drawable placeholder) {
                        status.setText(character ? "Loading character image…" : "Loading cover…");
                    }

                    @Override
                    public void onSuccess(android.graphics.drawable.Drawable drawable) {
                        image.setImageDrawable(drawable);
                        fit.setEnabled(true);
                        zoom.setEnabled(true);
                        status.setText("Pinch to zoom · Double-tap to fit");
                    }

                    @Override
                    public void onError(android.graphics.drawable.Drawable error) {
                        CoverStore.get(MainActivity.this).ensure(source, file -> {
                            if (closed[0] || isDestroyed()) return;
                            if (file == null) {
                                status.setText("Image unavailable. No saved thumbnail yet.");
                                return;
                            }
                            loading[1] = coil.Coil.imageLoader(MainActivity.this).enqueue(
                                    new coil.request.ImageRequest.Builder(MainActivity.this).data(file)
                                            .size(CoverStore.MAX_EDGE, CoverStore.MAX_EDGE).scale(coil.size.Scale.FIT)
                                            .target(new coil.target.Target() {
                                                @Override public void onSuccess(android.graphics.drawable.Drawable saved) {
                                                    if (closed[0]) return;
                                                    image.setImageDrawable(saved);
                                                    fit.setEnabled(true); zoom.setEnabled(true);
                                                    status.setText(character ? "Original unavailable · Saved character preview" : "Original unavailable · Saved cover preview");
                                                }
                                                @Override public void onError(android.graphics.drawable.Drawable missing) {
                                                    status.setText("Saved image unavailable. Choose another image.");
                                                }
                                            }).build());
                        });
                    }
                }).build();
        loading[0] = coil.Coil.imageLoader(this).enqueue(request);
        dialog.setOnDismissListener(d -> {
            closed[0] = true;
            for (coil.request.Disposable task : loading) if (task != null) task.dispose();
            image.setImageDrawable(null);
        });
        dialog.show();
        if (window != null) window.setLayout(-1, -1);
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }

    private void updateCoverPreview() {
        if (editorPreview == null || editorForm == null) return;
        String source = text(editorCover);
        if (!source.equals(selectedImageUri)) {
            editorCoverX = 0.5f;
            editorCoverY = 0.5f;
            editorCoverZoom = 1f;
            selectedImageUri = source;
        }
        int index = editorForm.indexOfChild(editorPreview);
        editorForm.removeView(editorPreview);
        editorPreview = coverView(source, "List thumbnail preview", 100, 144, editorCoverX, editorCoverY, editorCoverZoom);
        editorForm.addView(editorPreview, index, lp(dp(100), dp(144)));
        editorPositionSummary.setText(positionSummary(editorCoverX, editorCoverY));
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(16), dp(20), dp(24));
        return form;
    }

    private void storyRow(LinearLayout parent, long id, String label, String value, int field) {
        boolean empty = value == null || value.isEmpty();
        MediaEntity media = viewModel.repository.media(id);
        int accent = media == null ? ACCENT : mediaAccent(media);
        if (parent.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(tint(SURFACE, BORDER, .65f));
            parent.addView(divider, lp(-1, dp(1)));
            margin(divider, 0, 0, 8, 0);
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(16), 0, dp(16));
        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(label, 11, MUTED);
        heading.setAllCaps(true);
        heading.setLetterSpacing(.09f);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(0, dp(4), 0, 0);
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        textColumn.addView(heading, lp(-1, -2));
        row.addView(textColumn, lp(0, -2, 1));
        if (!empty) {
            TextView body = label(value, field == 0 ? 18 : 16, TEXT);
            if (field == 0) body.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            body.setLineSpacing(dp(3), 1.08f);
            body.setMaxLines(4);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setContentDescription(label + ": " + value);
            add(textColumn, body, 8, 0);
            TextView expand = detailTextAction("Show more", accent, v -> {
                boolean collapsed = body.getMaxLines() == 4;
                body.setMaxLines(collapsed ? Integer.MAX_VALUE : 4);
                ((TextView) v).setText(collapsed ? "Show less" : "Show more");
                v.setContentDescription((collapsed ? "Show less " : "Show more ") + label.toLowerCase(Locale.ROOT));
            });
            expand.setContentDescription("Show more " + label.toLowerCase(Locale.ROOT));
            expand.setVisibility(View.GONE);
            textColumn.addView(expand, lp(-2, -2));
            // Evaluate after layout; short values never reserve an empty button row.
            body.post(() -> {
                android.text.Layout layout = body.getLayout();
                if (layout != null && layout.getLineCount() > 0)
                    expand.setVisibility(layout.getEllipsisCount(layout.getLineCount() - 1) > 0 || layout.getLineCount() > 4 ? View.VISIBLE : View.GONE);
            });
            row.addView(detailEditIcon("Edit " + label.toLowerCase(Locale.ROOT), accent,
                    v -> editStory(id, label, value, field)), lp(dp(48), dp(48)));
        } else {
            TextView addValue = detailTextAction("+ Add " + label.toLowerCase(Locale.ROOT), accent,
                    v -> editStory(id, label, value, field));
            textColumn.addView(addValue, lp(-2, -2));
        }
        parent.addView(row, lp(-1, -2));
    }

    private TextView detailTextAction(String text, int accent, View.OnClickListener click) {
        TextView view = label(text, 13, accent);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setMinHeight(dp(48));
        view.setMinWidth(dp(48));
        view.setPadding(0, dp(6), dp(12), dp(6));
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(withAlpha(accent, 35)), null, bg(Color.WHITE, 8)));
        view.setOnClickListener(click);
        return view;
    }

    private android.widget.ImageButton detailEditIcon(String description, int accent, View.OnClickListener click) {
        android.widget.ImageButton button = new androidx.appcompat.widget.AppCompatImageButton(this);
        button.setImageResource(R.drawable.ic_edit);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
        android.graphics.drawable.Drawable surface = new android.graphics.drawable.InsetDrawable(
                bg(tint(SURFACE, accent, .065f), 10), dp(8));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(withAlpha(accent, 45)), surface,
                new android.graphics.drawable.InsetDrawable(bg(Color.WHITE, 10), dp(8))));
        button.setPadding(dp(15), dp(15), dp(15), dp(15));
        button.setContentDescription(description);
        button.setOnClickListener(click);
        return button;
    }

    private void editStory(long id, String label, String value, int field) {
        editTextDialog(label, value, true, (text, saved, failed) -> {
            StoryMemoryEntity old = viewModel.repository.story(id), story = new StoryMemoryEntity();
            story.mediaId = id;
            if (old != null) {
                story.mainCharacterName = old.mainCharacterName;
                story.storySummary = old.storySummary;
                story.lastStoryPoint = old.lastStoryPoint;
                story.importantNotes = old.importantNotes;
            }
            switch (field) {
                case 0:
                    story.mainCharacterName = text;
                    break;
                case 1:
                    story.storySummary = text;
                    break;
                case 2:
                    story.lastStoryPoint = text;
                    break;
                default:
                    story.importantNotes = text;
            }
            write(() -> viewModel.repository.saveStory(story), () -> { saved.run(); showDetail(id); }, failed);
        });
    }

    private interface NoteSave {
        void save(String value, Runnable saved, Runnable failed);
    }

    private void editTextDialog(String title, String value, boolean multiline, NoteSave save) {
        LinearLayout form = dialogForm();
        TextInputEditText input = field(form, title, value, multiline);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(title).setView(scroll(form))
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        boolean[] saving = {false};
        Runnable persist = () -> {
            if (saving[0]) return;
            saving[0] = true;
            dialog.getButton(-1).setEnabled(false);
            save.save(text(input), dialog::dismiss, () -> { saving[0] = false; dialog.getButton(-1).setEnabled(true); });
        };
        protectNoteDraft(dialog, java.util.Collections.singletonList(input), persist, () -> saving[0]);
        dialog.show();
    }

    /** Cancel, system Back and an outside tap all use the same draft decision. */
    private void protectNoteDraft(androidx.appcompat.app.AlertDialog editor, List<TextInputEditText> fields,
                                  Runnable save, java.util.function.BooleanSupplier saving) {
        List<String> baseline = new ArrayList<>();
        for (TextInputEditText field : fields) baseline.add(field.getText() == null ? "" : field.getText().toString());
        androidx.appcompat.app.AlertDialog[] prompt = {null};
        Runnable leave = () -> {
            if (saving.getAsBoolean()) { if (!editor.isShowing()) editor.show(); return; }
            boolean changed = false;
            for (int i = 0; i < fields.size(); i++) {
                String current = fields.get(i).getText() == null ? "" : fields.get(i).getText().toString();
                if (!current.equals(baseline.get(i))) { changed = true; break; }
            }
            if (!changed) { editor.dismiss(); return; }
            // Outside-tap cancellation has already hidden the window; retain the draft beneath the prompt.
            if (!editor.isShowing()) editor.show();
            if (prompt[0] != null && prompt[0].isShowing()) return;
            prompt[0] = new MaterialAlertDialogBuilder(this).setTitle("Keep your changes?")
                    .setMessage("Keep saves what you wrote. Discard removes these unsaved changes; previously saved notes stay unchanged.")
                    .setNegativeButton("Discard", (d, which) -> editor.dismiss())
                    .setPositiveButton("Keep", (d, which) -> save.run()).create();
            prompt[0].show();
        };
        editor.setOnShowListener(d -> {
            editor.getButton(-1).setOnClickListener(v -> save.run());
            editor.getButton(-2).setOnClickListener(v -> leave.run());
        });
        editor.getOnBackPressedDispatcher().addCallback(new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leave.run(); }
        });
        editor.setOnCancelListener(d -> leave.run());
    }

    private void editTracking(MediaEntity m, UserProgressEntity p) {
        String[] values = {"plan_to_read", "reading", "completed", "dropped"}, labels = new String[4];
        for (int i = 0; i < 4; i++) labels[i] = statusLabel(values[i], m.type);
        new MaterialAlertDialogBuilder(this).setTitle("Tracking status")
                .setSingleChoiceItems(labels, Arrays.asList(values).indexOf(p.trackingStatus), (d, w) -> {
                    d.dismiss();
                    write(() -> viewModel.repository.updateTracking(m.id, values[w], p.rating, p.notes), () -> showDetail(m.id));
                }).setNegativeButton("Cancel", null).show();
    }

    private void editRating(MediaEntity m, UserProgressEntity p) {
        String[] labels = new String[11];
        labels[0] = "Unrated";
        for (int i = 1; i <= 10; i++) labels[i] = i + " / 10";
        new MaterialAlertDialogBuilder(this).setTitle("Rating").setSingleChoiceItems(labels, p.rating == null ? 0 : p.rating, (d, w) -> {
            d.dismiss();
            write(() -> viewModel.repository.updateTracking(m.id, p.trackingStatus, w == 0 ? null : w, p.notes), () -> showDetail(m.id));
        }).setNegativeButton("Cancel", null).show();
    }

    private int genreUsage(long id) {
        int count = 0;
        for (MediaEntity m : viewModel.repository.media())
            for (GenreEntity g : viewModel.repository.genresFor(m.id))
                if (g.id == id) {
                    count++;
                    break;
                }
        return count;
    }

    private void renderEditorGenres() {
        editorGenreContainer.removeAllViews();
        com.google.android.material.chip.ChipGroup group = new com.google.android.material.chip.ChipGroup(this);
        for (GenreEntity g : viewModel.repository.genres())
            selectionChip(group, g.name, editorGenres.contains(g.id), selected -> {
                if (selected) editorGenres.add(g.id);
                else editorGenres.remove(g.id);
            });
        for (String name : new ArrayList<>(importedGenreNames))
            selectionChip(group, name + " (new)", true, selected -> {
                if (selected) importedGenreNames.add(name); else importedGenreNames.remove(name);
            });
        editorGenreContainer.addView(group);
    }

    private android.os.Bundle captureDraft() {
        android.os.Bundle b = new android.os.Bundle();
        if (editorTitle == null) return b;
        b.putString("title", text(editorTitle));
        b.putString("type", editorType.getSelectedItem().toString());
        b.putString("release", editorRelease.getSelectedItem().toString());
        b.putString("cover", text(editorCover));
        b.putFloat("coverX", editorCoverX);
        b.putFloat("coverY", editorCoverY);
        b.putFloat("coverZoom", editorCoverZoom);
        b.putString("progress", text(editorProgress));
        b.putString("rating", text(editorRating));
        b.putString("tracking", editorTracking.getSelectedItem().toString());
        b.putString("notes", text(editorNotes));
        b.putBoolean("favorite", editorFavorite.isChecked());
        b.putString("importedType", importedTypeName);
        b.putStringArrayList("importedGenres", new ArrayList<>(importedGenreNames));
        if (editingId == null && editorImportStatus != null) b.putString("importMessage", editorImportStatus.getText().toString());
        b.putLongArray("genres", editorGenres.stream().sorted().mapToLong(Long::longValue).toArray());
        return b;
    }

    private String draftKey(android.os.Bundle b) {
        StringBuilder result = new StringBuilder();
        for (String key : Arrays.asList("title", "type", "release", "cover", "progress", "rating", "tracking", "notes")) {
            String value = b.getString(key, "");
            result.append(value.length()).append(':').append(value);
        }
        result.append(b.getString("importedType", "")).append(b.getStringArrayList("importedGenres"));
        return result.append(b.getBoolean("favorite")).append(Arrays.toString(b.getLongArray("genres")))
                .append(':').append(b.getFloat("coverX", 0.5f)).append(':').append(b.getFloat("coverY", 0.5f))
                .append(':').append(b.getFloat("coverZoom", 1f)).toString();
    }

    private void saveEditor(MediaEntity existing) {
        if (editorSaving) return;
        if (text(editorTitle).isEmpty()) {
            editorTitle.setError("Title is required");
            editorTitle.requestFocus();
            return;
        }
        double progress;
        Integer rating;
        try {
            progress = parseDouble(text(editorProgress), 0);
        } catch (IllegalArgumentException e) {
            editorProgress.setError(e.getMessage());
            editorProgress.requestFocus();
            return;
        }
        try {
            rating = parseRating(text(editorRating));
        } catch (IllegalArgumentException e) {
            editorRating.setError(e.getMessage());
            editorRating.requestFocus();
            return;
        }
        String cover = text(editorCover);
        if (!cover.isEmpty()) {
            Uri uri = Uri.parse(cover);
            String scheme = uri.getScheme();
            if (!"content".equals(scheme) && (!"https".equals(scheme) && !"http".equals(scheme) || uri.getHost() == null || uri.getHost().isEmpty())) {
                editorCover.setError("Use an http(s) image URL or choose a device image");
                editorCover.requestFocus();
                return;
            }
        }
        MediaEntity m = new MediaEntity();
        m.id = existing == null ? 0 : existing.id;
        m.createdAt = existing == null ? 0 : existing.createdAt;
        m.title = text(editorTitle);
        m.type = editorType.getSelectedItem().toString();
        m.releaseStatus = editorRelease.getSelectedItem().toString();
        m.coverImage = cover.isEmpty() ? null : cover;
        m.isFavorite = editorFavorite.isChecked();
        m.coverPositionX = cover.equals(selectedImageUri) ? editorCoverX : 0.5f;
        m.coverPositionY = cover.equals(selectedImageUri) ? editorCoverY : 0.5f;
        m.coverZoom = cover.equals(selectedImageUri) ? editorCoverZoom : 1f;
        UserProgressEntity p = new UserProgressEntity();
        p.currentProgress = progress;
        p.rating = rating;
        p.trackingStatus = editorTracking.getSelectedItem().toString();
        p.notes = text(editorNotes);
        List<Long> genres = new ArrayList<>(editorGenres);
        List<String> newGenres = new ArrayList<>(importedGenreNames);
        String newType = importedTypeName;
        cancelChapterImport();
        editorSaving = true;
        editorSaveButton.setEnabled(false);
        editorSaveButton.setText("Saving…");
        CoverStore.get(this).ensure(m.coverImage, file -> {
            if (isDestroyed() || isFinishing()) return;
            if (m.coverImage != null && file == null)
                toast("Cover could not be saved offline. Keep the original and try again when available.");
            ProgressHistoryEntity[] change = {null};
            write(() -> viewModel.repository.saveImportedMedia(m, p, genres, newGenres, newType, recorded -> change[0] = recorded), () -> {
                editorSaving = false;
                formBaseline = null;
                formDraft = null;
                detailOrigin = editorOrigin.equals("stats") ? "stats" : "home";
                showDetail(m.id);
                offerProgressUndo(change[0]);
            });
        });
    }

}
