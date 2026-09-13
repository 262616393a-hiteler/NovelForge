package com.novelcraft.writer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

public class ChapterSelectActivity extends android.app.Activity {

    private ListView listView;
    private TextView tvHeading;
    private Button btnBack, btnAll, btnReview;
    private CheckBox cbAutoRevise;

    private String[] titles;
    private boolean[] checked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chapter_select);

        listView = findViewById(R.id.listView);
        tvHeading = findViewById(R.id.tvHeading);
        btnBack = findViewById(R.id.btnBack);
        btnAll = findViewById(R.id.btnAll);
        btnReview = findViewById(R.id.btnReview);
        cbAutoRevise = findViewById(R.id.cbAutoRevise);
        cbAutoRevise.setChecked(getSharedPreferences("editor_prefs", MODE_PRIVATE)
                .getBoolean("default_auto_revise", false));

        titles = getIntent().getStringArrayExtra("titles");
        if (titles == null) titles = new String[0];
        checked = new boolean[titles.length];

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, titles);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            checked[position] = listView.isItemChecked(position);
        });

        btnBack.setOnClickListener(v -> finish());

        btnAll.setOnClickListener(v -> {
            for (int i = 0; i < titles.length; i++) {
                listView.setItemChecked(i, true);
                checked[i] = true;
            }
        });

        btnReview.setOnClickListener(v -> {
            int count = 0;
            for (boolean b : checked) if (b) count++;
            if (count == 0) {
                android.widget.Toast.makeText(this, R.string.select_none, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            int[] indices = new int[count];
            int k = 0;
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) indices[k++] = i;
            }
            Intent data = new Intent();
            data.putExtra("indices", indices);
            data.putExtra("autoRevise", cbAutoRevise.isChecked());
            setResult(RESULT_OK, data);
            finish();
        });
    }
}
