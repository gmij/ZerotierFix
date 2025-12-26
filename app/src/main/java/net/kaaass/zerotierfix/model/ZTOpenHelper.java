package net.kaaass.zerotierfix.model;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.greenrobot.greendao.database.Database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ZTOpenHelper extends DaoMaster.OpenHelper {
    static final String TAG = "ZTOpenHelper";

    public ZTOpenHelper(Context context, String str) {
        super(context, str);
    }

    public ZTOpenHelper(Context context, String str, SQLiteDatabase.CursorFactory cursorFactory) {
        super(context, str, cursorFactory);
    }

    @Override
    public void onUpgrade(Database db, int oldVersion, int newVersion) {
        Log.i(TAG, "Upgrading schema from version " + oldVersion + " to " + newVersion);
        for (Migration migration : getMigrations()) {
            if (oldVersion < migration.getVersion()) {
                migration.runMigration(db);
            }
        }
    }

    @Override
    public void onDowngrade(Database db, int oldVersion, int newVersion) {
        Log.i(TAG, "Downgrading schema from version " + oldVersion + " to " + newVersion);
        // Handle downgrade from schema 23 (per-app VPN) to schema 22
        if (oldVersion == 23 && newVersion == 22) {
            Log.i(TAG, "Removing per-app VPN routing tables and columns");
            try {
                // Drop the APP_ROUTING table if it exists
                db.execSQL("DROP TABLE IF EXISTS APP_ROUTING");
                Log.i(TAG, "Dropped APP_ROUTING table");
            } catch (android.database.SQLException e) {
                Log.e(TAG, "Error dropping APP_ROUTING table", e);
            }
            
            // Leave perAppRouting column - SQLite doesn't support DROP COLUMN and unused columns are harmless
            // The column will be ignored by the generated DAO code and won't affect functionality
            Log.i(TAG, "Preserving perAppRouting column in NETWORK_CONFIG (will be ignored by DAO)");
        } else {
            // For unsupported downgrade paths, log warning and attempt default behavior
            Log.w(TAG, "Unsupported downgrade path from " + oldVersion + " to " + newVersion + ", attempting default downgrade");
            super.onDowngrade(db, oldVersion, newVersion);
        }
    }

    private List<Migration> getMigrations() {
        ArrayList<Migration> migrations = new ArrayList<>();
        migrations.add(new MigrationV18());
        migrations.add(new MigrationV19());
        migrations.add(new MigrationV20());
        migrations.add(new MigrationV21());
        migrations.add(new MigrationV22());
        Collections.sort(migrations, (migration, migration2) -> migration.getVersion().compareTo(migration2.getVersion()));
        return migrations;
    }

    private interface Migration {
        Integer getVersion();

        void runMigration(Database database);
    }

    private static class MigrationV18 implements Migration {
        private MigrationV18() {
        }

        @Override
        public Integer getVersion() {
            return 18;
        }

        @Override
        public void runMigration(Database database) {
            database.execSQL("ALTER TABLE NETWORK ADD COLUMN " + NetworkDao.Properties.LastActivated.columnName + " BOOLEAN NOT NULL DEFAULT FALSE ");
        }
    }

    private static class MigrationV19 implements Migration {
        private MigrationV19() {
        }

        @Override
        public Integer getVersion() {
            return 19;
        }

        @Override
        public void runMigration(Database database) {
            database.execSQL("ALTER TABLE NETWORK_CONFIG ADD COLUMN " + NetworkConfigDao.Properties.DnsMode.columnName + " INTEGER NOT NULL DEFAULT 0 ");
        }
    }

    private static class MigrationV20 implements Migration {
        private MigrationV20() {
        }

        @Override
        public Integer getVersion() {
            return 20;
        }

        @Override
        public void runMigration(Database database) {
            database.execSQL("UPDATE NETWORK_CONFIG SET " + NetworkConfigDao.Properties.DnsMode.columnName + " = 2 WHERE " + NetworkConfigDao.Properties.UseCustomDNS.columnName + " = 1 ");
        }
    }

    private static class MigrationV21 implements Migration {
        private MigrationV21() {
        }

        @Override
        public Integer getVersion() {
            return 21;
        }

        @Override
        public void runMigration(Database database) {
            MoonOrbitDao.createTable(database, true);
        }
    }

    private static class MigrationV22 implements Migration {
        private MigrationV22() {
        }

        @Override
        public Integer getVersion() {
            return 22;
        }

        @Override
        public void runMigration(Database database) {
            database.execSQL("ALTER TABLE MOON_ORBIT ADD COLUMN " + MoonOrbitDao.Properties.FromFile.columnName + " INTEGER NOT NULL DEFAULT 0 ");
        }
    }
}
