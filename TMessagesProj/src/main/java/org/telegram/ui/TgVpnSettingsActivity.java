package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.vpn.TgVpnController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Экран встроенного VPN: тумблер, токен подписки и режим. Ноду ядро подбирает само —
 * первую, через которую проходит проба, поэтому списка серверов тут намеренно нет.
 */
public class TgVpnSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_ENABLE = 0;
    private static final int ROW_STATUS = 1;
    private static final int ROW_HEADER = 2;
    private static final int ROW_TOKEN = 3;
    private static final int ROW_MODE = 4;
    private static final int ROW_INFO = 5;
    private static final int ROW_COUNT = 6;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_INFO = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_SETTING = 3;

    private static final int REQUEST_VPN_CONSENT = 9931;

    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("VPN");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter());
        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_ENABLE) {
                toggle((TextCheckCell) view);
            } else if (position == ROW_TOKEN) {
                showTokenDialog();
            } else if (position == ROW_MODE) {
                showModeDialog();
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void toggle(TextCheckCell cell) {
        TgVpnController controller = TgVpnController.getInstance();
        boolean enable = !controller.isEnabled();
        if (enable && TextUtils.isEmpty(controller.getToken())) {
            showTokenDialog();
            return;
        }
        if (enable && TgVpnController.MODE_TUN.equals(controller.getMode())) {
            // Согласие на VPN спрашивает система и только из Activity.
            Intent consent = TgVpnController.vpnConsentIntent(getParentActivity() == null ? getContext() : getParentActivity());
            if (consent != null && getParentActivity() != null) {
                getParentActivity().startActivityForResult(consent, REQUEST_VPN_CONSENT);
                return;
            }
        }
        controller.setEnabled(enable);
        cell.setChecked(enable);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_VPN_CONSENT) {
            // Отказ в согласии — не тупик: контроллер сам уйдёт в прокси-режим.
            TgVpnController.getInstance().setEnabled(true);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    private void showTokenDialog() {
        if (getParentActivity() == null) {
            return;
        }
        TgVpnController controller = TgVpnController.getInstance();
        EditText editText = new EditText(getParentActivity());
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint("токен подписки");
        editText.setSingleLine(true);
        editText.setText(controller.getToken());
        editText.setPadding(dp(24), dp(8), dp(24), dp(8));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Подписка");
        builder.setView(editText);
        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            controller.setSubscription(editText.getText().toString(), controller.getBaseUrl());
            if (controller.isEnabled()) {
                controller.restart("token changed");
            }
            adapter.notifyDataSetChanged();
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void showModeDialog() {
        if (getParentActivity() == null) {
            return;
        }
        TgVpnController controller = TgVpnController.getInstance();
        CharSequence[] items = new CharSequence[]{
                "Туннель — звонки тоже через VPN",
                "Прокси — без системного VPN, звонки по TCP"
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Режим");
        builder.setItems(items, (dialog, which) -> {
            controller.setMode(which == 0 ? TgVpnController.MODE_TUN : TgVpnController.MODE_PROXY);
            if (controller.isEnabled()) {
                controller.restart("mode changed");
            }
            adapter.notifyDataSetChanged();
        });
        showDialog(builder.create());
    }

    private String statusText() {
        TgVpnController controller = TgVpnController.getInstance();
        String status = controller.getStatus();
        StringBuilder text = new StringBuilder();
        if (TgVpnController.STATUS_CONNECTED.equals(status)) {
            text.append("Подключено: ").append(controller.getNodeName());
            text.append(TgVpnController.MODE_TUN.equals(controller.getActiveMode())
                    ? "\nРежим: туннель (звонки внутри VPN)"
                    : "\nРежим: прокси (звонки по TCP-реле, групповые — мимо VPN)");
        } else if (TgVpnController.STATUS_CONNECTING.equals(status)) {
            text.append("Подключение…");
        } else if (TgVpnController.STATUS_ERROR.equals(status)) {
            text.append("Ошибка подключения");
        } else {
            text.append("Выключено");
        }
        if (!TextUtils.isEmpty(controller.getLastError())) {
            text.append("\n").append(controller.getLastError());
        }
        return text.toString();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == ROW_ENABLE || position == ROW_TOKEN || position == ROW_MODE;
        }

        @Override
        public int getItemViewType(int position) {
            switch (position) {
                case ROW_ENABLE:
                    return VIEW_TYPE_CHECK;
                case ROW_HEADER:
                    return VIEW_TYPE_HEADER;
                case ROW_TOKEN:
                case ROW_MODE:
                    return VIEW_TYPE_SETTING;
                default:
                    return VIEW_TYPE_INFO;
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_CHECK:
                    view = new TextCheckCell(parent.getContext());
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(parent.getContext());
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_SETTING:
                    view = new TextSettingsCell(parent.getContext());
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new TextInfoPrivacyCell(parent.getContext());
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TgVpnController controller = TgVpnController.getInstance();
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck("VPN только для Telegram", controller.isEnabled(), false);
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText("Подписка");
                    break;
                }
                case VIEW_TYPE_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == ROW_TOKEN) {
                        String token = controller.getToken();
                        // Токен — секрет, показываем только хвост
                        String shown = TextUtils.isEmpty(token) ? "не задан"
                                : "…" + token.substring(Math.max(0, token.length() - 6));
                        cell.setTextAndValue("Токен", shown, true);
                    } else {
                        cell.setTextAndValue("Режим",
                                TgVpnController.MODE_TUN.equals(controller.getMode()) ? "туннель" : "прокси", false);
                    }
                    break;
                }
                default: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == ROW_STATUS) {
                        cell.setText(statusText());
                    } else {
                        cell.setText("В режиме туннеля в VPN попадает только трафик Telegram — "
                                + "остальные приложения телефона идут напрямую. Звонки, включая групповые, "
                                + "тоже идут через ваш сервер. Если туннель занят другим VPN-приложением, "
                                + "включится запасной прокси-режим.");
                    }
                    break;
                }
            }
        }
    }
}
