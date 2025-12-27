package net.kaaass.zerotierfix.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.model.AppInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying selected apps with remove buttons
 */
public class SelectedAppsAdapter extends RecyclerView.Adapter<SelectedAppsAdapter.ViewHolder> {
    
    private List<AppInfo> selectedApps = new ArrayList<>();
    private OnAppRemovedListener onAppRemovedListener;

    public interface OnAppRemovedListener {
        void onAppRemoved(AppInfo app);
    }

    public SelectedAppsAdapter(OnAppRemovedListener listener) {
        this.onAppRemovedListener = listener;
    }

    public void setSelectedApps(List<AppInfo> apps) {
        this.selectedApps = apps;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_selected_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = selectedApps.get(position);
        
        holder.appName.setText(app.getAppName());
        holder.appIcon.setImageDrawable(app.getIcon());
        
        holder.removeButton.setOnClickListener(v -> {
            if (onAppRemovedListener != null) {
                onAppRemovedListener.onAppRemoved(app);
            }
        });
    }

    @Override
    public int getItemCount() {
        return selectedApps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView appName;
        ImageButton removeButton;

        ViewHolder(View view) {
            super(view);
            appIcon = view.findViewById(R.id.app_icon);
            appName = view.findViewById(R.id.app_name);
            removeButton = view.findViewById(R.id.remove_app_button);
        }
    }
}
