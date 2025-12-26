package net.kaaass.zerotierfix.model;

import android.content.Context;
import android.database.SQLException;
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
            Log.i(TAG, "Removing per-app VPN routing features");
            try {
                // Drop the APP_ROUTING table if it exists
                db.execSQL("DROP TABLE IF EXISTS APP_ROUTING");
                Log.i(TAG, "Dropped APP_ROUTING table");
            } catch (SQLException e) {
                Log.e(TAG, "Error dropping APP_ROUTING table", e);
            }
            
            try {
                // Remove the perAppRouting column from NETWORK_CONFIG by recreating the table
                // This is necessary because SQLite doesn't support DROP COLUMN before version 3.35.5
                Log.i(TAG, "Recreating NETWORK_CONFIG table without perAppRouting column");
                
                // Create temporary table with the correct schema (without perAppRouting)
                db.execSQL("CREATE TABLE NETWORK_CONFIG_TEMP (" +
                        "_id INTEGER PRIMARY KEY, " +
                        "TYPE INTEGER, " +
                        "STATUS INTEGER, " +
                        "MAC TEXT, " +
                        "MTU TEXT, " +
                        "BROADCAST INTEGER, " +
                        "BRIDGING INTEGER, " +
                        "ROUTE_VIA_ZERO_TIER INTEGER NOT NULL, " +
                        "USE_CUSTOM_DNS INTEGER, " +
                        "DNS_MODE INTEGER NOT NULL)");
                
                // Copy data from old table to new table (excluding perAppRouting column)
                db.execSQL("INSERT INTO NETWORK_CONFIG_TEMP " +
                        "(_id, TYPE, STATUS, MAC, MTU, BROADCAST, BRIDGING, ROUTE_VIA_ZERO_TIER, USE_CUSTOM_DNS, DNS_MODE) " +
                        "SELECT _id, TYPE, STATUS, MAC, MTU, BROADCAST, BRIDGING, ROUTE_VIA_ZERO_TIER, USE_CUSTOM_DNS, DNS_MODE " +
                        "FROM NETWORK_CONFIG");
                
                // Drop old table
                db.execSQL("DROP TABLE NETWORK_CONFIG");
                
                // Rename temporary table to original name
                db.execSQL("ALTER TABLE NETWORK_CONFIG_TEMP RENAME TO NETWORK_CONFIG");
                
                Log.i(TAG, "Successfully recreated NETWORK_CONFIG table");
            } catch (SQLException e) {
                Log.e(TAG, "Error recreating NETWORK_CONFIG table", e);
                // If recreation fails, try to continue - the app might still work with the extra column
            }
        } else {
            // For unsupported downgrade paths, log warning and delegate to default behavior
            // Note: super.onDowngrade() typically throws SQLiteException for downgrades
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
