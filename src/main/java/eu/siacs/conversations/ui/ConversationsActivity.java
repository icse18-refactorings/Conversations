/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.IdRes;
import android.support.annotation.NonNull;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import org.openintents.openpgp.util.OpenPgpApi;

import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityConversationsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.interfaces.OnConversationArchived;
import eu.siacs.conversations.ui.interfaces.OnConversationRead;
import eu.siacs.conversations.ui.interfaces.OnConversationSelected;
import eu.siacs.conversations.ui.interfaces.OnConversationsListItemUpdated;
import eu.siacs.conversations.ui.service.EmojiService;
import eu.siacs.conversations.ui.util.ActivityResult;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;

import static eu.siacs.conversations.ui.ConversationFragment.REQUEST_DECRYPT_PGP;

public class ConversationsActivity extends XmppActivity implements OnConversationSelected, OnConversationArchived, OnConversationsListItemUpdated, OnConversationRead, XmppConnectionService.OnAccountUpdate, XmppConnectionService.OnConversationUpdate, XmppConnectionService.OnRosterUpdate, OnUpdateBlocklist, XmppConnectionService.OnShowErrorToast {

	public static final String ACTION_VIEW_CONVERSATION = "eu.siacs.conversations.action.VIEW";
	public static final String EXTRA_CONVERSATION = "conversationUuid";
	public static final String EXTRA_DOWNLOAD_UUID = "eu.siacs.conversations.download_uuid";
	public static final String EXTRA_TEXT = "text";
	public static final String EXTRA_NICK = "nick";
	public static final String EXTRA_IS_PRIVATE_MESSAGE = "pm";

	public static final int REQUEST_OPEN_MESSAGE = 0x9876;
	public static final int REQUEST_PLAY_PAUSE = 0x5432;


	//secondary fragment (when holding the conversation, must be initialized before refreshing the overview fragment
	private static final @IdRes
	int[] FRAGMENT_ID_NOTIFICATION_ORDER = {R.id.secondary_fragment, R.id.main_fragment};
	private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
	private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();
	private ActivityConversationsBinding binding;
	private boolean mActivityPaused = true;
	private AtomicBoolean mRedirectInProcess = new AtomicBoolean(false);

	private static boolean isViewIntent(Intent i) {
		return i != null && ACTION_VIEW_CONVERSATION.equals(i.getAction()) && i.hasExtra(EXTRA_CONVERSATION);
	}

	private static Intent createLauncherIntent(Context context) {
		final Intent intent = new Intent(context, ConversationsActivity.class);
		intent.setAction(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_LAUNCHER);
		return intent;
	}

	@Override
	protected void refreshUiReal() {
		for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
			refreshFragment(id);
		}
	}

	@Override
	void onBackendConnected() {
		if (performRedirectIfNecessary(true)) {
			return;
		}
		xmppConnectionService.getNotificationService().setIsInForeground(true);
		Intent intent = pendingViewIntent.pop();
		if (intent != null) {
			if (processViewIntent(intent)) {
				if (binding.secondaryFragment != null) {
					notifyFragmentOfBackendConnected(R.id.main_fragment);
				}
				invalidateActionBarTitle();
				return;
			}
		}
		for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
			notifyFragmentOfBackendConnected(id);
		}

		ActivityResult activityResult = postponedActivityResult.pop();
		if (activityResult != null) {
			handleActivityResult(activityResult);
		}

		invalidateActionBarTitle();
		if (binding.secondaryFragment != null && ConversationFragment.getConversation(this) == null) {
			Conversation conversation = ConversationsOverviewFragment.getSuggestion(this);
			if (conversation != null) {
				openConversation(conversation, null);
			}
		}
		showDialogsIfMainIsOverview();
	}

	private boolean performRedirectIfNecessary(boolean noAnimation) {
		return performRedirectIfNecessary(null, noAnimation);
	}

	private boolean performRedirectIfNecessary(final Conversation ignore, final boolean noAnimation) {
		if (xmppConnectionService == null) {
			return false;
		}
		boolean isConversationsListEmpty = xmppConnectionService.isConversationsListEmpty(ignore);
		if (isConversationsListEmpty && mRedirectInProcess.compareAndSet(false, true)) {
			final Intent intent = getRedirectionIntent(noAnimation);
			runOnUiThread(() -> {
				startActivity(intent);
				if (noAnimation) {
					overridePendingTransition(0, 0);
				}
			});
		}
		return mRedirectInProcess.get();
	}

	private Intent getRedirectionIntent(boolean noAnimation) {
		Account pendingAccount = xmppConnectionService.getPendingAccount();
		Intent intent;
		if (pendingAccount != null) {
			intent = new Intent(this, EditAccountActivity.class);
			intent.putExtra("jid", pendingAccount.getJid().asBareJid().toString());
		} else {
			if (xmppConnectionService.getAccounts().size() == 0) {
				if (Config.X509_VERIFICATION) {
					intent = new Intent(this, ManageAccountActivity.class);
				} else if (Config.MAGIC_CREATE_DOMAIN != null) {
					intent = new Intent(this, WelcomeActivity.class);
					WelcomeActivity.addInviteUri(intent, getIntent());
				} else {
					intent = new Intent(this, EditAccountActivity.class);
				}
			} else {
				intent = new Intent(this, StartConversationActivity.class);
			}
		}
		intent.putExtra("init", true);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		if (noAnimation) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
		}
		return intent;
	}

	private void showDialogsIfMainIsOverview() {
		if (xmppConnectionService == null) {
			return;
		}
		final Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
		if (fragment != null && fragment instanceof ConversationsOverviewFragment) {
			if (ExceptionHelper.checkForCrash(this)) {
				return;
			}
			openBatteryOptimizationDialogIfNeeded();
		}
	}

	private String getBatteryOptimizationPreferenceKey() {
		@SuppressLint("HardwareIds") String device = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
		return "show_battery_optimization" + (device == null ? "" : device);
	}

	private void setNeverAskForBatteryOptimizationsAgain() {
		getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply();
	}

	private void openBatteryOptimizationDialogIfNeeded() {
		if (hasAccountWithoutPush()
				&& isOptimizingBattery()
				&& getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true)) {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(R.string.battery_optimizations_enabled);
			builder.setMessage(R.string.battery_optimizations_enabled_dialog);
			builder.setPositiveButton(R.string.next, (dialog, which) -> {
				Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
				Uri uri = Uri.parse("package:" + getPackageName());
				intent.setData(uri);
				try {
					startActivityForResult(intent, REQUEST_BATTERY_OP);
				} catch (ActivityNotFoundException e) {
					Toast.makeText(this, R.string.device_does_not_support_battery_op, Toast.LENGTH_SHORT).show();
				}
			});
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
				builder.setOnDismissListener(dialog -> setNeverAskForBatteryOptimizationsAgain());
			}
			AlertDialog dialog = builder.create();
			dialog.setCanceledOnTouchOutside(false);
			dialog.show();
		}
	}

	private boolean hasAccountWithoutPush() {
		for (Account account : xmppConnectionService.getAccounts()) {
			if (account.getStatus() == Account.State.ONLINE && !xmppConnectionService.getPushManagementService().available(account)) {
				return true;
			}
		}
		return false;
	}

	private void notifyFragmentOfBackendConnected(@IdRes int id) {
		final Fragment fragment = getFragmentManager().findFragmentById(id);
		if (fragment != null && fragment instanceof OnBackendConnected) {
			((OnBackendConnected) fragment).onBackendConnected();
		}
	}

	private void refreshFragment(@IdRes int id) {
		final Fragment fragment = getFragmentManager().findFragmentById(id);
		if (fragment != null && fragment instanceof XmppFragment) {
			((XmppFragment) fragment).refresh();
		}
	}

	private boolean processViewIntent(Intent intent) {
		String uuid = intent.getStringExtra(EXTRA_CONVERSATION);
		Conversation conversation = uuid != null ? xmppConnectionService.findConversationByUuid(uuid) : null;
		if (conversation == null) {
			Log.d(Config.LOGTAG, "unable to view conversation with uuid:" + uuid);
			return false;
		}
		openConversation(conversation, intent.getExtras());
		return true;
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,@NonNull String permissions[], @NonNull int[] grantResults) {
		UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults);
		if (grantResults.length > 0) {
			if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				switch(requestCode) {
					case REQUEST_OPEN_MESSAGE:
						refreshUiReal();
						ConversationFragment.openPendingMessage(this);
						break;
					case REQUEST_PLAY_PAUSE:
						ConversationFragment.startStopPending(this);
						break;
				}
			}
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, final Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
		if (xmppConnectionService != null) {
			handleActivityResult(activityResult);
		} else {
			this.postponedActivityResult.push(activityResult);
		}
	}

	private void handleActivityResult(ActivityResult activityResult) {
		if (activityResult.resultCode == Activity.RESULT_OK) {
			handlePositiveActivityResult(activityResult.requestCode, activityResult.data);
		} else {
			handleNegativeActivityResult(activityResult.requestCode);
		}
	}

	private void handleNegativeActivityResult(int requestCode) {
		Conversation conversation = ConversationFragment.getConversationReliable(this);
		switch (requestCode) {
			case REQUEST_DECRYPT_PGP:
				if (conversation == null) {
					break;
				}
				conversation.getAccount().getPgpDecryptionService().giveUpCurrentDecryption();
				break;
			case REQUEST_BATTERY_OP:
				setNeverAskForBatteryOptimizationsAgain();
				break;
		}
	}

	private void handlePositiveActivityResult(int requestCode, final Intent data) {
		Conversation conversation = ConversationFragment.getConversationReliable(this);
		if (conversation == null) {
			Log.d(Config.LOGTAG,"conversation not found");
			return;
		}
		switch (requestCode) {
			case REQUEST_DECRYPT_PGP:
				conversation.getAccount().getPgpDecryptionService().continueDecryption(data);
				break;
			case REQUEST_CHOOSE_PGP_ID:
				long id = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0);
				if (id != 0) {
					conversation.getAccount().setPgpSignId(id);
					announcePgp(conversation.getAccount(), null, null, onOpenPGPKeyPublished);
				} else {
					choosePgpSignId(conversation.getAccount());
				}
				break;
			case REQUEST_ANNOUNCE_PGP:
				announcePgp(conversation.getAccount(), conversation, data, onOpenPGPKeyPublished);
				break;
		}
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		new EmojiService(this).init();
		this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations);
		this.getFragmentManager().addOnBackStackChangedListener(this::invalidateActionBarTitle);
		this.getFragmentManager().addOnBackStackChangedListener(this::showDialogsIfMainIsOverview);
		this.initializeFragments();
		this.invalidateActionBarTitle();
		final Intent intent;
		if (savedInstanceState == null) {
			intent = getIntent();
		} else {
			intent = savedInstanceState.getParcelable("intent");
		}
		if (isViewIntent(intent)) {
			pendingViewIntent.push(intent);
			setIntent(createLauncherIntent(this));
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.activity_conversations, menu);
		MenuItem qrCodeScanMenuItem = menu.findItem(R.id.action_scan_qr_code);
		if (qrCodeScanMenuItem != null) {
			Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
			boolean visible = getResources().getBoolean(R.bool.show_qr_code_scan)
					&& fragment != null
					&& fragment instanceof ConversationsOverviewFragment;
			qrCodeScanMenuItem.setVisible(visible);
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public void onConversationSelected(Conversation conversation) {
		if (ConversationFragment.getConversation(this) == conversation) {
			Log.d(Config.LOGTAG,"ignore onConversationSelected() because conversation is already open");
			return;
		}
		openConversation(conversation, null);
	}

	private void openConversation(Conversation conversation, Bundle extras) {
		ConversationFragment conversationFragment = (ConversationFragment) getFragmentManager().findFragmentById(R.id.secondary_fragment);
		final boolean mainNeedsRefresh;
		if (conversationFragment == null) {
			mainNeedsRefresh = false;
			Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
			if (mainFragment != null && mainFragment instanceof ConversationFragment) {
				conversationFragment = (ConversationFragment) mainFragment;
			} else {
				conversationFragment = new ConversationFragment();
				FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
				fragmentTransaction.replace(R.id.main_fragment, conversationFragment);
				fragmentTransaction.addToBackStack(null);
				fragmentTransaction.commit();
			}
		} else {
			mainNeedsRefresh = true;
		}
		conversationFragment.reInit(conversation, extras == null ? new Bundle() : extras);
		if (mainNeedsRefresh) {
			refreshFragment(R.id.main_fragment);
		} else {
			invalidateActionBarTitle();
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				FragmentManager fm = getFragmentManager();
				if (fm.getBackStackEntryCount() > 0) {
					fm.popBackStack();
					return true;
				}
				break;
			case R.id.action_scan_qr_code:
				UriHandlerActivity.scan(this);
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		Intent pendingIntent = pendingViewIntent.peek();
		savedInstanceState.putParcelable("intent", pendingIntent != null ? pendingIntent : getIntent());
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	protected void onStart() {
		final int theme = findTheme();
		if (this.mTheme != theme) {
			this.mSkipBackgroundBinding = true;
			recreate();
		} else {
			this.mSkipBackgroundBinding = false;
		}
		mRedirectInProcess.set(false);
		super.onStart();
	}

	@Override
	protected void onNewIntent(final Intent intent) {
		if (isViewIntent(intent)) {
			if (xmppConnectionService != null) {
				processViewIntent(intent);
			} else {
				pendingViewIntent.push(intent);
			}
		}
		setIntent(createLauncherIntent(this));
	}

	@Override
	public void onPause() {
		this.mActivityPaused = true;
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
		this.mActivityPaused = false;
	}

	private void initializeFragments() {
		FragmentTransaction transaction = getFragmentManager().beginTransaction();
		Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
		Fragment secondaryFragment = getFragmentManager().findFragmentById(R.id.secondary_fragment);
		if (mainFragment != null) {
			if (binding.secondaryFragment != null) {
				if (mainFragment instanceof ConversationFragment) {
					getFragmentManager().popBackStack();
					transaction.remove(mainFragment);
					transaction.commit();
					getFragmentManager().executePendingTransactions();
					transaction = getFragmentManager().beginTransaction();
					transaction.replace(R.id.secondary_fragment, mainFragment);
					transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
					transaction.commit();
					return;
				}
			} else {
				if (secondaryFragment != null && secondaryFragment instanceof ConversationFragment) {
					transaction.remove(secondaryFragment);
					transaction.commit();
					getFragmentManager().executePendingTransactions();
					transaction = getFragmentManager().beginTransaction();
					transaction.replace(R.id.main_fragment, secondaryFragment);
					transaction.addToBackStack(null);
					transaction.commit();
					return;
				}
			}
		} else {
			transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
		}
		if (binding.secondaryFragment != null && secondaryFragment == null) {
			transaction.replace(R.id.secondary_fragment, new ConversationFragment());
		}
		transaction.commit();
	}

	private void invalidateActionBarTitle() {
		final ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
			if (mainFragment != null && mainFragment instanceof ConversationFragment) {
				final Conversation conversation = ((ConversationFragment) mainFragment).getConversation();
				if (conversation != null) {
					actionBar.setTitle(conversation.getName());
					actionBar.setDisplayHomeAsUpEnabled(true);
					return;
				}
			}
			actionBar.setTitle(R.string.app_name);
			actionBar.setDisplayHomeAsUpEnabled(false);
		}
	}

	@Override
	public void onConversationArchived(Conversation conversation) {
		if (performRedirectIfNecessary(conversation, false)) {
			return;
		}
		Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
		if (mainFragment != null && mainFragment instanceof ConversationFragment) {
			getFragmentManager().popBackStack();
			return;
		}
		Fragment secondaryFragment = getFragmentManager().findFragmentById(R.id.secondary_fragment);
		if (secondaryFragment != null && secondaryFragment instanceof ConversationFragment) {
			if (((ConversationFragment) secondaryFragment).getConversation() == conversation) {
				Conversation suggestion = ConversationsOverviewFragment.getSuggestion(this, conversation);
				if (suggestion != null) {
					openConversation(suggestion, null);
				}
			}
		}
	}

	@Override
	public void onConversationsListItemUpdated() {
		Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
		if (fragment != null && fragment instanceof ConversationsOverviewFragment) {
			((ConversationsOverviewFragment) fragment).refresh();
		}
	}

	@Override
	public void switchToConversation(Conversation conversation) {
		Log.d(Config.LOGTAG,"override");
		openConversation(conversation,null);
	}

	@Override
	public void onConversationRead(Conversation conversation) {
		if (!mActivityPaused && pendingViewIntent.peek() == null) {
			xmppConnectionService.sendReadMarker(conversation);
		} else {
			Log.d(Config.LOGTAG,"ignoring read callback. mActivityPaused="+Boolean.toString(mActivityPaused));
		}
	}

	@Override
	public void onAccountUpdate() {
		this.refreshUi();
	}

	@Override
	public void onConversationUpdate() {
		if (performRedirectIfNecessary(false)) {
			return;
		}
		this.refreshUi();
	}

	@Override
	public void onRosterUpdate() {
		this.refreshUi();
	}

	@Override
	public void OnUpdateBlocklist(OnUpdateBlocklist.Status status) {
		this.refreshUi();
	}

	@Override
	public void onShowErrorToast(int resId) {
		runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
	}
}
