package hk.edu.hkmu.speakerdiarazationdemo;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import hk.edu.hkmu.speakerdiarazationdemo.ecosys.SessionMode;
import hk.edu.hkmu.speakerdiarazationdemo.ecosys.SessionModeStore;

/**
 * The first screen: pick the deaf/hearing role. Exactly two choices; the pick is
 * persisted so a later launch skips straight in. Rough by intent — no design system.
 */
public class RoleChooserActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A stored pick skips the chooser: relaunching the app proceeds directly.
        SessionMode stored = SessionModeStore.load(this);
        if (stored != null) {
            proceed(stored);
            return;
        }

        setContentView(R.layout.activity_role_chooser);

        Button btnDeaf = findViewById(R.id.btnRoleDeaf);
        Button btnHearing = findViewById(R.id.btnRoleHearing);
        btnDeaf.setOnClickListener(v -> choose(SessionMode.DEAF));
        btnHearing.setOnClickListener(v -> choose(SessionMode.HEARING));
    }

    private void choose(SessionMode mode) {
        SessionModeStore.save(this, mode);
        proceed(mode);
    }

    private void proceed(SessionMode mode) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_SESSION_MODE, mode.name());
        startActivity(intent);
        Toast.makeText(this, "身份：" + mode.label(), Toast.LENGTH_SHORT).show();
        finish();
    }
}
