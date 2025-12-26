package net.kaaass.zerotierfix.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.model.AppInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用路由列表的适配器
 */
public class AppRoutingAdapter extends RecyclerView.Adapter<AppRoutingAdapter.AppViewHolder> {
    private List<AppInfo> appList;
    private OnAppRoutingChangeListener listener;

    public interface OnAppRoutingChangeListener {
        void onAppRoutingChanged(AppInfo app, boolean routeViaVpn);
    }

    public AppRoutingAdapter(OnAppRoutingChangeListener listener) {
        this.appList = new ArrayList<>();
        this.listener = listener;
    }

    public void setAppList(List<AppInfo> appList) {
        this.appList = appList;
        notifyDataSetChanged();
    }

    public List<AppInfo> getAppList() {
        return appList;
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_app_routing, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo app = appList.get(position);
        holder.bind(app);
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    class AppViewHolder extends RecyclerView.ViewHolder {
        private ImageView appIcon;
        private TextView appName;
        private TextView appPackage;
        private TextView appType;
        private CheckBox routeCheckbox;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.app_icon);
            appName = itemView.findViewById(R.id.app_name);
            appPackage = itemView.findViewById(R.id.app_package);
            appType = itemView.findViewById(R.id.app_type);
            routeCheckbox = itemView.findViewById(R.id.app_route_checkbox);
        }

        public void bind(AppInfo app) {
            appIcon.setImageDrawable(app.getAppIcon());
            appName.setText(app.getAppName());
            appPackage.setText(app.getPackageName());
            appType.setText(app.isSystemApp() 
                ? itemView.getContext().getString(R.string.system_app)
                : itemView.getContext().getString(R.string.user_app));

            // 设置复选框状态，但暂时不触发监听器
            routeCheckbox.setOnCheckedChangeListener(null);
            routeCheckbox.setChecked(app.isRouteViaVpn());

            // 设置复选框监听器
            routeCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                app.setRouteViaVpn(isChecked);
                if (listener != null) {
                    listener.onAppRoutingChanged(app, isChecked);
                }
            });

            // 点击整个item也可以切换复选框
            itemView.setOnClickListener(v -> routeCheckbox.setChecked(!routeCheckbox.isChecked()));
        }
    }
}
