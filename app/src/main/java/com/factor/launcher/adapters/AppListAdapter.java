package com.factor.launcher.adapters;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.*;
import android.view.animation.AccelerateDecelerateInterpolator;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.RecyclerView;
import com.factor.launcher.R;
import com.factor.launcher.databinding.AppListItemBinding;
import com.factor.launcher.view_models.AppListManager;
import com.factor.launcher.models.NotificationHolder;
import com.factor.launcher.models.UserApp;
import com.factor.launcher.util.Constants;
import com.factor.launcher.util.Payload;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;

//adapter for app drawer
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppListViewHolder>
{
    public Activity activity;

    private ArrayList<UserApp> userApps;

    private final boolean displayHidden;

    private AppListManager appListManager;


    public AppListAdapter(AppListManager appListManager, ArrayList<UserApp> userApps, boolean displayHidden, Activity activity)
    {
        this.userApps = userApps;
        this.activity = activity;
        this.appListManager = appListManager;
        this.displayHidden = displayHidden;
    }


    //not needed anymore
    public void invalidate()
    {
        this.activity = null;
        this.appListManager = null;
        this.userApps = null;
    }



    //broadcast when app is renamed to scroll to new position
    public void renameBroadCast(int position)
    {
        Intent intent = new Intent();
        intent.setAction(Constants.BROADCAST_ACTION_RENAME);
        intent.putExtra(Constants.RENAME_KEY, position);
        activity.getApplicationContext().sendBroadcast(intent);
    }



    //find app object given package name
    private UserApp findAppByPackage(String packageName)
    {
        ArrayList<UserApp> copyApps = new ArrayList<>(userApps);

        for (UserApp a : copyApps)
            if (a.getPackageName().equals(packageName)) return a;

        return new UserApp();
    }



    //clear all notifications
    public void clearAllNotifications()
    {
        ArrayList<UserApp> copyList = new ArrayList<>(userApps);

        for (UserApp app : copyList)
        {
            UserApp appToUpdate = findAppByPackage(app.getPackageName());
            appToUpdate.resetNotifications();
            notifyItemChanged(userApps.indexOf(appToUpdate), new Payload(new NotificationHolder(0, "", "")));
            if (appToUpdate.isPinned())
                appListManager.getFactorManager().clearNotification(appToUpdate);
        }

    }



    //received notifications
    public void onReceivedNotification(Intent intent)
    {
        try
        {
            UserApp app = findAppByPackage(intent.getStringExtra(Constants.NOTIFICATION_INTENT_PACKAGE_KEY));
            NotificationHolder notificationHolder =
                    new NotificationHolder(intent.getIntExtra(Constants.NOTIFICATION_INTENT_ID_KEY, 0),
                            intent.getStringExtra(Constants.NOTIFICATION_INTENT_TITLE_TEXT_KEY),
                            intent.getStringExtra(Constants.NOTIFICATION_INTENT_CONTENT_TEXT_KEY));
            Payload payload = new Payload(notificationHolder);
            if (userApps.contains(app))
            {
                if (app.incrementNotificationCount(notificationHolder))
                    notifyItemChanged(userApps.indexOf(app), payload);

                if (app.isPinned())
                    appListManager.getFactorManager().onReceivedNotification(intent, app, payload);


                Log.d("payload", app.getNotificationTitle() + " created payload");
            }
        }
        catch (ConcurrentModificationException ex)
        {
            //if app list is being modified, wait 5 seconds for another attempt
            SystemClock.sleep(5000);
            onReceivedNotification(intent);
        }

    }

    //cleared notification
    public void onClearedNotification(Intent intent)
    {
        UserApp app = findAppByPackage(intent.getStringExtra(Constants.NOTIFICATION_INTENT_PACKAGE_KEY));
        NotificationHolder notificationHolder =
                new NotificationHolder(intent.getIntExtra(Constants.NOTIFICATION_INTENT_ID_KEY, 0), "", "");
        Payload payload = new Payload(notificationHolder);
        if (userApps.contains(app))
        {
            Log.d("payload", app.getPackageName() + " created payload");
            if (app.decreaseNotificationCount(payload.getNotificationHolder().getId()))
                notifyItemChanged(userApps.indexOf(app), payload);

            if (app.isPinned())
                appListManager.getFactorManager().onClearedNotification(intent, app, payload);
        }
    }




    @SuppressLint("ClickableViewAccessibility")
    @NonNull
    @Override
    public AppListViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        int id;
        if (displayHidden)
            id = viewType == 1 ? R.layout.hidden_app : R.layout.app_list_item;
        else
            id = viewType == 1 ? R.layout.app_list_item : R.layout.hidden_app;


        View view = LayoutInflater.from(parent.getContext()).inflate(id, parent, false);

        activity.registerForContextMenu(view);

        AppListViewHolder holder =  new AppListViewHolder(view, this);

        assert holder.binding != null;
        if (holder.binding instanceof AppListItemBinding)
        {
            ((AppListItemBinding)holder.binding).labelEdit.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus)
                    activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
                else
                    activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            });

            view.setOnCreateContextMenuListener((menu, v, menuInfo) ->
            {
                MenuInflater inflater = activity.getMenuInflater();
                inflater.inflate(R.menu.app_list_item_menu, menu);
                if (((AppListItemBinding)holder.binding).getUserApp().isPinned())
                    menu.getItem(0).setEnabled(false);

                //add to home & remove from home
                menu.getItem(0).setOnMenuItemClickListener(item -> appListManager.changePin(((AppListItemBinding)holder.binding).getUserApp()));

                //edit
                SubMenu sub = menu.getItem(1).getSubMenu();

                //rename
                sub.getItem(0).setOnMenuItemClickListener(item ->
                {
                    holder.enterEditMode(((AppListItemBinding)holder.binding));
                    return true;
                });
                //hide
                MenuItem hide = sub.getItem(1);
                hide.setTitle(((AppListItemBinding) holder.binding).getUserApp().isHidden() ? "Show" : "Hide");
                hide.setOnMenuItemClickListener(item -> !((AppListItemBinding)holder.binding).getUserApp().isHidden() ?
                        appListManager.hideApp(((AppListItemBinding)holder.binding).getUserApp()) : appListManager.showApp(((AppListItemBinding)holder.binding).getUserApp()));
                //info
                menu.getItem(2).setOnMenuItemClickListener(item ->
                {
                    view.getContext().startActivity(
                            new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:"+((AppListItemBinding)holder.binding).getUserApp().getPackageName())));
                    return true;
                });

                //uninstall
                menu.getItem(3).setOnMenuItemClickListener(item ->
                {
                    view.getContext().startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:"+((AppListItemBinding)holder.binding).getUserApp().getPackageName()))
                            .putExtra(Intent.EXTRA_RETURN_RESULT, true));
                    return true;
                });
            });


            view.setOnTouchListener((v, event) ->
            {
                switch(event.getAction())
                {
                    case MotionEvent.ACTION_DOWN:
                        ((AppListItemBinding)holder.binding).itemHost.animateToClickedState();
                        ((AppListItemBinding)holder.binding).itemBackground.animate().alpha(1).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(150);
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ((AppListItemBinding)holder.binding).itemHost.animateBackFromClickedState();
                        ((AppListItemBinding)holder.binding).itemBackground.animate().alpha(0).setInterpolator(new AccelerateDecelerateInterpolator()).setDuration(150);
                        return false;
                }
                return false;
            });

        }


        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull AppListViewHolder holder, int position)
    {
        holder.bindApp(userApps.get(position));
    }


    @Override
    public void onViewRecycled(@NonNull AppListViewHolder holder)
    {
        super.onViewRecycled(holder);
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
    }

    @Override
    public void onBindViewHolder(@NonNull AppListViewHolder holder, int position, @NonNull List<Object> payloads)
    {
        if (!payloads.isEmpty() && (payloads.get(0) instanceof Payload))
        {
            ViewDataBinding binding = holder.binding;
            UserApp appToChange = userApps.get(position);
            if (binding instanceof AppListItemBinding)
            {
                //after receiving notification, change the notification counter
                if (appToChange.getCurrentNotifications().size() < 1 || appToChange.isBeingEdited())
                    ((AppListItemBinding) binding).notificationCount.setVisibility(View.GONE);
                else if (appToChange.getCurrentNotifications().size() > 0 || !appToChange.isBeingEdited())
                {
                    ((AppListItemBinding) binding).notificationCount.setVisibility(View.VISIBLE);
                    ((AppListItemBinding) binding).notificationCount.setText(appToChange.retrieveNotificationCount());
                }
            }
        }
        else
            onBindViewHolder(holder, position);
    }

    @Override
    public int getItemCount()
    {
        return userApps.size();
    }

    @Override
    public int getItemViewType(int position)
    {
        if (userApps.get(position).isHidden())
            return 0; //do not show
        else
            return 1; //show
    }


    //app drawer item ViewHolder
    protected static class AppListViewHolder extends RecyclerView.ViewHolder
    {
        private final ViewDataBinding binding;

        private final AppListAdapter appListAdapter;

        public AppListViewHolder(@NonNull View itemView, AppListAdapter appListAdapter)
        {
            super(itemView);
            binding = DataBindingUtil.bind(itemView);

            this.appListAdapter = appListAdapter;
        }

        public void bindApp(UserApp app)
        {
            if (binding instanceof AppListItemBinding)
            {
                AppListItemBinding appBinding = (AppListItemBinding)binding;
                appBinding.setUserApp(app);
                appBinding.labelEdit.setText(app.getLabelNew());
                try
                {
                    appBinding.icon.setImageDrawable(app.getIcon());
                }
                catch (kotlin.UninitializedPropertyAccessException ex)
                {
                    loadIcon(app);
                    appBinding.icon.setImageDrawable(app.getIcon());
                }


                appBinding.label.setVisibility(app.visibilityLabel());
                appBinding.labelEdit.setVisibility(app.visibilityEditing());
                appBinding.editButtonGroup.setVisibility(app.visibilityEditing());
                appBinding.notificationCount.setVisibility(app.visibilityNotificationCount());

                if (!app.isBeingEdited())
                {
                    setOnClickListener(app);
                    if (app.getCurrentNotifications().size() > 0)
                        appBinding.notificationCount.setText(app.retrieveNotificationCount());
                }
                else
                {
                    removeOnClickListener();
                    appBinding.labelEdit.setText(app.getLabelNew());
                    appBinding.cancelEditButton.setOnClickListener(view -> exitEditMode(app));
                    appBinding.resetEditButton.setOnClickListener(view -> resetEditMode(app));
                    appBinding.confirmEditButton.setOnClickListener(view ->
                    {
                        String newName = Objects.requireNonNull(appBinding.labelEdit.getText()).toString();
                        if (newName.isEmpty())
                            exitEditMode(app);
                        else if (app.isCustomized() && newName.equals(app.getLabelNew()))
                            exitEditMode(app);
                        else if (!app.isCustomized() && newName.equals(app.getLabelOld()))
                            exitEditMode(app);
                        else
                        {
                            exitEditMode(app);
                            renameApp(app, newName);
                        }
                    });
                }

            }
        }


        //enter edit mode for app
        public void enterEditMode(AppListItemBinding binding)
        {
            appListAdapter.activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
            UserApp app =  binding.getUserApp();
            app.setBeingEdited(true);
            removeOnClickListener();
            binding.labelEdit.setText(app.getLabelNew());
            binding.cancelEditButton.setOnClickListener(view -> exitEditMode(app));
            binding.resetEditButton.setOnClickListener(view -> resetEditMode(app));
            binding.confirmEditButton.setOnClickListener(view ->
            {
                String newName = Objects.requireNonNull(binding.labelEdit.getText()).toString();
                if (newName.isEmpty())
                    exitEditMode(app);
                else if (app.isCustomized() && newName.equals(app.getLabelNew()))
                    exitEditMode(app);
                else if (!app.isCustomized() && newName.equals(app.getLabelOld()))
                    exitEditMode(app);
                else
                {
                    exitEditMode(app);
                    renameApp(app, newName);
                }
            });
            appListAdapter.notifyItemChanged(appListAdapter.userApps.indexOf(app));
        }


        //exit edit mode for app
        private void exitEditMode(UserApp app)
        {
            appListAdapter.activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            setOnClickListener(app);
            ((AppListItemBinding)binding).labelEdit.clearFocus();
            app.setBeingEdited(false);
            appListAdapter.notifyItemChanged(appListAdapter.userApps.indexOf(app));
        }


        //reset edit mode for app
        private void resetEditMode(UserApp app)
        {
            app.setBeingEdited(false);
            if (!app.isCustomized())
                exitEditMode(app);
            else
                resetAppEdit(app);
        }

        private void setOnClickListener(UserApp app)
        {
            itemView.setOnClickListener(v ->
            {

                Intent intent = appListAdapter.appListManager.packageManager.getLaunchIntentForPackage(app.getPackageName());
                if (intent != null)
                    itemView.getContext().startActivity(intent,
                            ActivityOptions.makeClipRevealAnimation(itemView,0,0,100, 100).toBundle());
            });
        }

        private void removeOnClickListener()
        {
            itemView.setOnClickListener(v ->{});
        }



        //load icon for the app
        private void loadIcon(UserApp app)
        {
            try {
                if (appListAdapter.appListManager.packageManager.getApplicationInfo(app.getPackageName(), 0).enabled)
                    app.setIcon(appListAdapter.appListManager.packageManager.getApplicationIcon(app.getPackageName()));
            }
            catch (PackageManager.NameNotFoundException e)
            {
                //app package cannot be found
               appListAdapter.appListManager.removeAppFromDB(app);
            }
        }



        //edit app dialog
        public void renameApp(UserApp app, String newLabel)
        {
            if (!appListAdapter.userApps.contains(app)) return;

            app.setCustomized(true);
            app.setLabelNew(newLabel);
            appListAdapter.appListManager.isAfterRename = true;
            appListAdapter.appListManager.updateApp(app);
        }

        //reset app's name back to original label
        public void resetAppEdit(UserApp app)
        {
            if (!appListAdapter.userApps.contains(app)) return;

            app.setCustomized(false);
            app.setLabelNew(app.getLabelOld());
            appListAdapter.appListManager.isAfterRename = true;
            appListAdapter.appListManager.updateApp(app);
        }



        //start app shortcut
        private void startShortCut(ShortcutInfo shortcutInfo)
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
                appListAdapter.appListManager.launcherApps.startShortcut(shortcutInfo.getPackage(), shortcutInfo.getId(), null, null, Process.myUserHandle());
        }
    }
}