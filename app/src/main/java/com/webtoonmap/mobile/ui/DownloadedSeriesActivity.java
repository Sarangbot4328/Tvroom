package com.webtoonmap.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;

import java.util.List;

public final class DownloadedSeriesActivity extends AppCompatActivity {
    private String titleId;
    private EpisodeAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_series);
        SystemBarInsets.apply(this, findViewById(R.id.series_root), true);
        titleId = getIntent().getStringExtra("title_id");
        if (titleId == null) { finish(); return; }

        LibraryDatabase db = LibraryDatabase.get(this);
        SeriesItem series = db.getSeries(titleId);
        if (series == null) { finish(); return; }
        ((TextView) findViewById(R.id.title)).setText(series.title);
        String meta = (series.tags.isEmpty() ? "" : "# " + series.tags.replace(",", "  #") + "\n\n") + series.description;
        ((TextView) findViewById(R.id.meta)).setText(meta);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.episode_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new EpisodeAdapter(db.listEpisodes(titleId));
        list.setAdapter(adapter);
    }

    @Override protected void onResume() {
        super.onResume();
        if (adapter != null && titleId != null) {
            adapter.setEpisodes(LibraryDatabase.get(this).listEpisodes(titleId));
        }
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
        private List<EpisodeItem> episodes;
        EpisodeAdapter(List<EpisodeItem> episodes) { this.episodes = episodes; }
        void setEpisodes(List<EpisodeItem> episodes) {
            this.episodes = episodes;
            notifyDataSetChanged();
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_episode, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            EpisodeItem episode = episodes.get(position);
            h.number.setText(episode.number + "화");
            h.title.setText(episode.title);
            h.count.setText(episode.imageCount + "장" + (episode.viewed ? " · 읽음" : ""));
            h.itemView.setBackgroundColor(ContextCompat.getColor(DownloadedSeriesActivity.this,
                    episode.viewed ? R.color.episode_viewed : R.color.surface));
            h.number.setTextColor(ContextCompat.getColor(DownloadedSeriesActivity.this,
                    episode.viewed ? R.color.text_secondary : R.color.green_dark));
            h.title.setTextColor(ContextCompat.getColor(DownloadedSeriesActivity.this,
                    episode.viewed ? R.color.text_secondary : R.color.text_primary));
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(DownloadedSeriesActivity.this, OfflineViewerActivity.class);
                intent.putExtra("title_id", episode.titleId);
                intent.putExtra("episode_no", episode.number);
                startActivity(intent);
            });
        }

        @Override public int getItemCount() { return episodes.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView number, title, count;
            Holder(View view) {
                super(view);
                number = view.findViewById(R.id.number);
                title = view.findViewById(R.id.title);
                count = view.findViewById(R.id.count);
            }
        }
    }
}
