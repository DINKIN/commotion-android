/*
 *  This file is part of Commotion Mesh Tether
 *  Copyright (C) 2010 by Szymon Jakubczak
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.commotionwireless.meshtether;

import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import net.commotionwireless.olsrinfo.datatypes.OlsrDataDump;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TabHost;
import android.widget.TabHost.OnTabChangeListener;
import android.widget.TextView;

public class StatusActivity extends android.app.TabActivity {
	private MeshTetherApp app;

	private TabHost tabs;
	private ImageButton onoff;
	private Button chooseProfile;
	private AlertDialog.Builder profileDialogBuilder;

	private boolean paused;

	private TextView textDownloadRate;
	private TextView textUploadRate;

	final static int DLG_ABOUT = 0;
	final static int DLG_ROOT = 1;
	final static int DLG_ERROR = 2;
	final static int DLG_SUPPLICANT = 3;

	private final static String LINKS = "links";
	private final static String INFO = "info";

	static NumberFormat nf = NumberFormat.getInstance();
	static {
		nf.setMaximumFractionDigits(2);
		nf.setMinimumFractionDigits(2);
		nf.setMinimumIntegerDigits(1);
	}

    ProgressDialog mProgressDialog;
    final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            mProgressDialog.setMessage(msg.getData().getString("message"));
        }
    };

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		app = (MeshTetherApp)getApplication();

		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		setProgressBarIndeterminate(true);
		setContentView(R.layout.main);

		// control interface
		onoff = (ImageButton) findViewById(R.id.onoff);
		onoff.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				int state = app.getState();
				if (state == MeshService.STATE_STOPPED) {
					chooseProfile.setEnabled(false);
					onoff.setImageResource(R.drawable.comlogo_sm_on);
					app.startService();
				} else {
					app.stopService();
					onoff.setImageResource(R.drawable.comlogo_sm_off);
					chooseProfile.setEnabled(true);
					update();
				}
			}
		});

		profileDialogBuilder = new AlertDialog.Builder(this);
		profileDialogBuilder.setTitle(R.string.choose_profile);

		chooseProfile = (Button) findViewById(R.id.choose_profile);
		chooseProfile.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				findProfilesOnDisk();
				final String[] profilesArray = app.profiles.toArray(new String[0]);
				profileDialogBuilder.setItems(profilesArray, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						app.activeProfile = profilesArray[which];
						chooseProfile.setText(app.activeProfile);
					}
				});
				profileDialogBuilder.create().show();
			}
		});

		tabs = getTabHost();
		tabs.addTab(tabs.newTabSpec(LINKS)
				.setIndicator(LINKS, getResources().getDrawable(R.drawable.ic_tab_contacts))
				.setContent(new Intent(this, LinksActivity.class)));
		tabs.addTab(tabs.newTabSpec(INFO)
				.setIndicator(INFO, getResources().getDrawable(R.drawable.ic_tab_recent))
				.setContent(new Intent(this, InfoActivity.class)));
		tabs.setOnTabChangedListener(new OnTabChangeListener() {
			@Override
			public void onTabChanged(String tabId) {
				update();
				// force refresh of up/down stats
				if (app.service != null)
					app.service.statsRequest(0);
				if (INFO.equals(tabId))
					app.infoActivity.update();
				if (app.linksActivity != null)
					if (LINKS.equals(tabId))
						app.linksActivity.mPauseOlsrInfoThread = false;
					else
						app.linksActivity.mPauseOlsrInfoThread = true;
			}
		});

		app.setStatusActivity(this);
		paused = false;

		textDownloadRate = ((TextView)findViewById(R.id.download_rate));
		textUploadRate = ((TextView)findViewById(R.id.upload_rate));

		mProgressDialog = new ProgressDialog(this);
		mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
	}
	@Override
	protected void onDestroy() {
		super.onDestroy();
		app.setStatusActivity(null);
	}
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_prefs:
			startActivity(new Intent(this, SettingsActivity.class));
			return true;
		case R.id.menu_about:
			showDialog(DLG_ABOUT);
			return true;
		case R.id.menu_share_debug:
			zipAndShareFile(new File(NativeHelper.app_log, "olsrd.log"));
			return true;
		case R.id.menu_share_status:
			getOlsrdStatusAndShare();
			return true;
		}
		return(super.onOptionsItemSelected(item));
	}

	private void findProfilesOnDisk() {
		FileFilter filter = new FileFilter() {
			
			@Override
			public boolean accept(File pathname) {
				if (pathname.getAbsolutePath().endsWith(".properties"))
					return true;
				return false;
			}
		};
		Collection<File> all = new ArrayList<File>();
		Collections.addAll(all, NativeHelper.app_bin.listFiles(filter));
		Collections.addAll(all, NativeHelper.profileDir.listFiles(filter));
		for (File f : all) {
			Log.i(MeshTetherApp.TAG, "Searching for profile: " + f.getAbsolutePath());
			String path = f.getAbsolutePath();
			String profileName = path.substring(path.lastIndexOf("/") + 1, path.lastIndexOf(".properties"));
			app.profiles.add(profileName);
			app.profileProperties.put(profileName, path);
		}
	}

	private void getOlsrdStatusAndShare() {
		if (app.getState() == MeshService.STATE_RUNNING) {
			app.linksActivity.mPauseOlsrInfoThread = true;
			mProgressDialog.setMessage("Generating...");
			mProgressDialog.show();
			new Thread() {

				private void showMessage(int messageId) {
					String message = getString(messageId);
					Message msg = mHandler.obtainMessage();
					Bundle b = new Bundle();
					b.putString("message", message);
					msg.setData(b);
					mHandler.sendMessage(msg);
				}

				@Override
				public void run() {
					try {
						showMessage(R.string.gettingolsrinfo);
						OlsrDataDump dump = app.mJsonInfo.all();
						showMessage(R.string.writingfile);
						String filename;
						if (dump.uuid == null || dump.uuid.contentEquals(""))
							filename = new String("olsrd-status-" + dump.systemTime + ".json");
						else
							filename = new String("olsrd-status-" + dump.systemTime + "_" + dump.uuid + ".json");
						final File f = new File(NativeHelper.app_log, filename);
						FileWriter fw = new FileWriter(f);
						fw.write(dump.toString());
						fw.close();
						showMessage(R.string.zippingfile);
						zipAndShareFile(f);
					} catch (IOException e) {
						e.printStackTrace();
					}
					mProgressDialog.dismiss();
					app.linksActivity.mPauseOlsrInfoThread = false;
				}
			}.start();
		} else {
			// nothing to do, since we can't talk to olsrd
			app.updateToast(getString(R.string.olsrdnotrunning), true);
			return;
		}
	}

	private void zipAndShareFile(File f) {
		if (! NativeHelper.isSdCardPresent()) {
			app.updateToast("Cannot find SD card, needed for saving the zip file.", true);
			return;
		}
		if (! f.exists()) {
			app.updateToast(f.getAbsolutePath() + " does not exist!", true);
			return;
		}
		final File zipFile = new File(NativeHelper.publicFiles, f.getName() + ".zip");
		try {
			NativeHelper.zip(f, zipFile);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		Intent i = new Intent(android.content.Intent.ACTION_SEND);
		i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
		i.setType("application/zip");
		i.putExtra(Intent.EXTRA_SUBJECT, "log from Commotion Mesh Tether");
		i.putExtra(Intent.EXTRA_TEXT, "Attached is an log sent by Commotion Mesh Tether.  For more info, see:\nhttps://code.commotionwireless.net/projects/commotion-android");
		i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(zipFile));
		startActivity(Intent.createChooser(i, "How do you want to share?"));
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		// TODO: these should not create and remove dialogs, but restore and dismiss
		if (id == DLG_ABOUT) {
			return (new AlertDialog.Builder(this))
			.setIcon(android.R.drawable.ic_dialog_info)
			.setTitle(R.string.help_title)
			.setMessage(R.string.help_message)
					.setPositiveButton("Live Chat", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							Uri uri = Uri.parse(getString(R.string.ircUrl));
							startActivity(new Intent(Intent.ACTION_VIEW, uri));
						}})
						.setNeutralButton("Website", new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								Uri uri = Uri.parse(getString(R.string.websiteUrl));
								startActivity(new Intent(Intent.ACTION_VIEW, uri));
							}})
							.setNegativeButton("OK", new DialogInterface.OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog, int which) { removeDialog(DLG_ABOUT); }})
								.create();
		}
		if (id == DLG_ROOT) {
			return (new AlertDialog.Builder(this))
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setTitle("Root Access")
			.setMessage("MeshTether requires 'su' to access the hardware! Please, make sure you have root access.")
			.setPositiveButton("Help", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					Uri uri = Uri.parse(getString(R.string.rootUrl));
					startActivity(new Intent(Intent.ACTION_VIEW, uri));
				}})
				.setNegativeButton("Close", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) { removeDialog(DLG_ROOT); }})
					.create();
		}
		if (id == DLG_ERROR) {
			return (new AlertDialog.Builder(this))
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setTitle("Error")
			.setMessage("Unexpected error occured! Check the troubleshooting guide for the error printed in the log tab.")
			.setPositiveButton("Help", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					Uri uri = Uri.parse(getString(R.string.fixUrl));
					startActivity(new Intent(Intent.ACTION_VIEW, uri));
				}})
				.setNegativeButton("Close", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) { removeDialog(DLG_ERROR); }})
					.create();
		}
		if (id == DLG_SUPPLICANT) {
			return (new AlertDialog.Builder(this))
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setTitle("Supplicant not available")
			.setMessage("MeshTether had trouble starting wpa_supplicant. Try again but set 'Skip wpa_supplicant' in settings.")
			.setPositiveButton("Do it now!", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					app.prefs.edit().putBoolean(getString(R.string.lan_wext), true).commit();
					app.updateToast("Settings updated, try again...", true);
				}})
				.setNeutralButton("More info", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						Uri uri = Uri.parse(getString(R.string.wikiUrl));
						startActivity(new Intent(Intent.ACTION_VIEW, uri));
					}})
					.setNegativeButton("Close", new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) { removeDialog(DLG_ROOT); }})
						.create();
		}
		return null;
	}

	@Override
	protected void onPause() {
		super.onPause();
		paused = true;
	}
	@Override
	protected void onResume() {
		super.onResume();
		paused = false;
		update();
		app.cleanUpNotifications();
	}
	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		if (MeshTetherApp.ACTION_CLIENTS.equals(intent.getAction())) {
			getTabHost().setCurrentTab(0); // show links
		}
	}

	static String formatRate(long v) {
		if (v < 1048576)
			return nf.format(v /    1024.0f) + " KB";
		else
			return nf.format(v / 1048576.0f) + " MB";
	}

	void update() {
		int state = app.getState();

		if (state == MeshService.STATE_STOPPED) {
			if (textDownloadRate != null)
				textDownloadRate.setText("---");
			if (textUploadRate != null)
				textUploadRate.setText("---");
			return;
		}

		MeshService svc = app.service;
		if (svc == null) return; // unexpected race condition

		if (state == MeshService.STATE_STARTING) {
			setProgressBarIndeterminateVisibility(true);
			return;
		}

		if (state != MeshService.STATE_RUNNING) {
			// this is unexpected, but don't fail
			return;
		}

		// STATE_RUNNING
		setProgressBarIndeterminateVisibility(false);

		Util.TrafficStats stats = svc.stats;
		if (textDownloadRate != null)
			textDownloadRate.setText(formatRate(stats.rate.tx_bytes)+"/s");
		if (textUploadRate != null)
			textUploadRate.setText(formatRate(stats.rate.rx_bytes)+"/s");
		// only request stats when visible
		if (!paused)
			svc.statsRequest(1000);
	}
}
