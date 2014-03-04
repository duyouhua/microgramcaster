package com.squeed.microgramcaster;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.Cast.ApplicationConnectionResult;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.squeed.microgramcaster.channel.Command;
import com.squeed.microgramcaster.channel.CommandFactory;
import com.squeed.microgramcaster.channel.MicrogramCasterChannel;
import com.squeed.microgramcaster.media.IsoFileUtil;
import com.squeed.microgramcaster.media.MediaItem;
import com.squeed.microgramcaster.media.MediaStoreAdapter;
import com.squeed.microgramcaster.server.MyHTTPD;
import com.squeed.microgramcaster.server.WebServerService;
import com.squeed.microgramcaster.util.TimeFormatter;
import com.squeed.microgramcaster.util.WifiHelper;

/**
 * Start Activity for the MicrogramCaster Android app.
 * 
 * Lists castable files, starts the HTTP server through a Service Intent and
 * provides the Google Cast plumbing.
 * 
 * Derived from Google's android-helloworld examples at github.com (TODO add
 * full URL)
 * 
 * @author Erik
 * 
 */
public class MainActivity extends ActionBarActivity {

	private static final String TAG = "MainActivity";

	private static final String APP_NAME = "4E4599F7";

	private CastDevice mSelectedDevice;
	private MediaRouter mMediaRouter;
	private MediaRouteSelector mMediaRouteSelector;
	private MediaRouter.Callback mMediaRouterCallback;

	private GoogleApiClient mApiClient;
	private Cast.Listener mCastListener;
	private ConnectionCallbacks mConnectionCallbacks;
	private ConnectionFailedListener mConnectionFailedListener;

	private MicrogramCasterChannel mMicrogramCasterChannel;

	private boolean mApplicationStarted;
	private boolean mWaitingForReconnect;

	private MenuItem playIcon;
	private MenuItem pauseIcon;

	private SeekBar seekBar;
	private ArrayAdapterItem adapter;

	private TextView currentPosition;
	private TextView totalDuration;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(com.squeed.microgramcaster.R.layout.activity_main);

		//ActionBar actionBar = getSupportActionBar();
		// actionBar.setBackgroundDrawable(new ColorDrawable(
		// android.R.color.transparent));

		startWebServer();
		initMediaRouter();
		listVideoFiles();
		initSeekBar();
	}

	private void startWebServer() {
		Intent webServerService = new Intent(this, WebServerService.class);
		this.startService(webServerService);
	}

	private void initSeekBar() {
		currentPosition = (TextView) findViewById(R.id.currentPosition);
		totalDuration = (TextView) findViewById(R.id.totalDuration);
		seekBar = (SeekBar) findViewById(R.id.seekBar1);
		seekBar.setEnabled(false);
		// seekBar.setVisibility(SeekBar.INVISIBLE);
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				currentPosition.setText(TimeFormatter.formatTime(progress));
			}

			public void onStartTrackingTouch(SeekBar seekBar) {

			}

			public void onStopTrackingTouch(SeekBar seekBar) {
				seekBarHandler.removeCallbacksAndMessages(null);
				sendMessage(CommandFactory.buildSeekPositionCommand(seekBar.getProgress()));
			}

		});
	}

	private void showSeekbar() {
		seekBar.setEnabled(true);
		seekBar.setProgress(currentSeekbarPosition);
		// seekBar.setVisibility(SeekBar.VISIBLE);
		totalDuration.setVisibility(TextView.VISIBLE);
		currentPosition.setVisibility(TextView.VISIBLE);
	}

	private void hideSeekbar() {
		adapter.setSelectedPosition(-1);
		adapter.notifyDataSetChanged();
		seekBar.setProgress(0);
		seekBar.setEnabled(false);
		seekBarHandler.removeCallbacksAndMessages(null);
		// seekBar.setVisibility(SeekBar.INVISIBLE);
		totalDuration.setVisibility(TextView.INVISIBLE);
		currentPosition.setVisibility(TextView.INVISIBLE);
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Start media router discovery
		mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
				MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);

	}

	@Override
	protected void onPause() {
		if (isFinishing()) {
			// End media router discovery
			mMediaRouter.removeCallback(mMediaRouterCallback);
		}
		super.onPause();
	}

	@Override
	public void onDestroy() {
		teardown();
		super.onDestroy();
	}

	private void initMediaRouter() {
		mMediaRouter = MediaRouter.getInstance(getApplicationContext());
		mMediaRouteSelector = new MediaRouteSelector.Builder().addControlCategory(
				CastMediaControlIntent.categoryForCast(APP_NAME)).build();
		mMediaRouterCallback = new MediaRouterCallback();
	}

	int currentSeekbarPosition = 0;
	Handler seekBarHandler = new Handler();

	Runnable run = new Runnable() {

		@Override
		public void run() {
			updateSeekBar();
		}
	};

	private ListView listView;

	protected boolean landscape = true;

	public void updateSeekBar() {
		currentPosition.setVisibility(TextView.VISIBLE);
		seekBar.setProgress(currentSeekbarPosition++);
		seekBarHandler.postDelayed(run, 1000);
	}

	public void onEventPlaying(int positionSeconds) {
		currentSeekbarPosition = positionSeconds;
		seekBarHandler.removeCallbacksAndMessages(null);
		playIcon.setVisible(false);
		pauseIcon.setVisible(true);
		
		// Send a position request directly as the sync between what the html5 player callbacks says and the actual
		// time when this callback is invoked differ by a few seconds for some reason. It's a bit like 'playing'
		// fires 2-3 seconds before the playback actually starts.
		sendMessage(CommandFactory.buildRequestPositionCommand());
	}

	public void onEventPaused(int positionSeconds) {
		currentSeekbarPosition = positionSeconds;
		seekBarHandler.removeCallbacksAndMessages(null);
		playIcon.setVisible(true);
		pauseIcon.setVisible(false);
	}

	public void onEventFinished() {
		adapter.setSelectedPosition(-1);
		adapter.notifyDataSetChanged();
		currentSeekbarPosition = 0;
		seekBar.setProgress(0);
		seekBarHandler.removeCallbacksAndMessages(null);
		hideMediaControlIcons();
	}

	public void onRequestedPosition(int positionSeconds) {
		currentSeekbarPosition = positionSeconds;
		seekBarHandler.removeCallbacksAndMessages(null);
		updateSeekBar();
	}

	private void listVideoFiles() {
		final MediaStoreAdapter mediaStoreAdapter = new MediaStoreAdapter();
		ArrayList<MediaItem> mediaFiles = (ArrayList<MediaItem>) mediaStoreAdapter.findFiles(this);
		listView = (ListView) findViewById(com.squeed.microgramcaster.R.id.videoFiles);

		adapter = new ArrayAdapterItem(this, R.layout.listview_item, mediaFiles);
		listView.setAdapter(adapter);
		OnItemClickListener listener = new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> arg0, View listItemView, int position, long arg3) {
				playSelectedMedia(mediaStoreAdapter, adapter, listItemView, position);
			}
		};

		listView.setOnItemClickListener(listener);
		OnItemLongClickListener lcListener = new OnItemLongClickListener() {

			@Override
			public boolean onItemLongClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
				String fileName = (String) arg1.getTag();
				adapter.setSelectedPosition(arg2);
				adapter.notifyDataSetChanged();
				FileChannel fileChannel = mediaStoreAdapter.getFileChannel(MainActivity.this, fileName);
				String txt = IsoFileUtil.getInfo(fileChannel);
				Log.i(TAG, txt);
				Toast.makeText(MainActivity.this, txt, Toast.LENGTH_LONG).show();
				return true;
			}
		};
		listView.setOnItemLongClickListener(lcListener);
	}

	private void playSelectedMedia(final MediaStoreAdapter mediaStoreAdapter, final ArrayAdapterItem adapter,
			View arg1, int arg2) {
		if (mSelectedDevice == null || !mApiClient.isConnected()) {
			Toast.makeText(MainActivity.this, "No cast device selected", Toast.LENGTH_SHORT).show();
			adapter.setSelectedPosition(-1);
			adapter.notifyDataSetChanged();
			mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
					MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
			return;
		}

		adapter.setSelectedPosition(arg2);
		adapter.notifyDataSetChanged();
		String fileName = (String) arg1.getTag();
		MediaItem mi = mediaStoreAdapter.findFile(MainActivity.this, fileName);
		Long durationMillis = mi.getDuration();

		seekBar.setMax((int) (durationMillis / 1000L));
		currentPosition.setText(TimeFormatter.formatTime(0));
		totalDuration.setText(TimeFormatter.formatTime((int) (durationMillis / 1000L)));

		currentSeekbarPosition = 0;

		showSeekbar();
		if (mApiClient.isConnected()) {
			sendMessage(CommandFactory.buildPlayUrlCommand(buildMediaItemURL(fileName)));			
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		getMenuInflater().inflate(com.squeed.microgramcaster.R.menu.menu, menu);
		MenuItem mediaRouteMenuItem = menu.findItem(com.squeed.microgramcaster.R.id.media_route_menu_item);
		MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider) MenuItemCompat
				.getActionProvider(mediaRouteMenuItem);

		// Set the MediaRouteActionProvider selector for device discovery.
		if (mediaRouteActionProvider != null)
			mediaRouteActionProvider.setRouteSelector(mMediaRouteSelector);

		MenuItem refreshIcon = menu.findItem(com.squeed.microgramcaster.R.id.action_refresh);
		refreshIcon.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {

			@Override
			public boolean onMenuItemClick(MenuItem item) {
				listVideoFiles();
				return false;
			}
		});

		final MenuItem rotateIcon = menu.findItem(com.squeed.microgramcaster.R.id.action_rotate);
		rotateIcon.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {

			@Override
			public boolean onMenuItemClick(MenuItem item) {
				sendMessage(CommandFactory.buildToggleRotateCommand());
				landscape = !landscape;
				if(landscape ) {
					rotateIcon.setIcon(R.drawable.ic_menu_always_landscape_portrait);
				} else {
					rotateIcon.setIcon(R.drawable.ic_menu_always_landscape_portrait_blue);
				}
				return false;
			}
		});

		playIcon = menu.findItem(com.squeed.microgramcaster.R.id.action_play);
		playIcon.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {

			@Override
			public boolean onMenuItemClick(MenuItem item) {
				sendMessage(CommandFactory.buildPlayCommand());
				return false;
			}
		});
		pauseIcon = menu.findItem(com.squeed.microgramcaster.R.id.action_pause);
		pauseIcon.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {

			@Override
			public boolean onMenuItemClick(MenuItem item) {
				sendMessage(CommandFactory.buildPauseCommand());
				playIcon.setVisible(true);
				pauseIcon.setVisible(false);
				return false;
			}
		});
		hideMediaControlIcons();
		return true;
	}

	/**
	 * An extension of the MediaRoute.Callback so we can invoke our own onRoute
	 * selected/unselected
	 */
	private class MediaRouterCallback extends MediaRouter.Callback {

		@Override
		public void onRouteSelected(MediaRouter router, android.support.v7.media.MediaRouter.RouteInfo route) {
			Log.i(TAG, "onRouteSelected: " + route);
			mSelectedDevice = CastDevice.getFromBundle(route.getExtras());
			launchReceiver();
		}

		@Override
		public void onRouteUnselected(MediaRouter router, android.support.v7.media.MediaRouter.RouteInfo route) {
			Log.i(TAG, "onRouteUnselected: " + route);
			teardown();
			mSelectedDevice = null;
			hideMediaControlIcons();
			hideSeekbar();

		}

	}

	private void hideMediaControlIcons() {
		playIcon.setVisible(false);
		pauseIcon.setVisible(false);
	}

	/**
	 * Start the receiver app
	 */
	private void launchReceiver() {
		try {
			mCastListener = new Cast.Listener() {

				@Override
				public void onApplicationDisconnected(int errorCode) {
					Log.d(TAG, "application has stopped");
					teardown();
				}

			};
			// Connect to Google Play services
			mConnectionCallbacks = new ConnectionCallbacks();

			mConnectionFailedListener = new ConnectionFailedListener();
			Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions.builder(mSelectedDevice, mCastListener);
			mApiClient = new GoogleApiClient.Builder(this).addApi(Cast.API, apiOptionsBuilder.build())
					.addConnectionCallbacks(mConnectionCallbacks)
					.addOnConnectionFailedListener(mConnectionFailedListener).build();

			mApiClient.connect();
		} catch (Exception e) {
			Log.e(TAG, "Failed launchReceiver", e);
		}
	}

	/**
	 * Tear down the connection to the receiver
	 */
	private void teardown() {
		if (mApiClient != null) {
			if (mApplicationStarted) {
				try {
					Cast.CastApi.stopApplication(mApiClient);
					if (mMicrogramCasterChannel != null) {
						Cast.CastApi.removeMessageReceivedCallbacks(mApiClient, mMicrogramCasterChannel.getNamespace());
						mMicrogramCasterChannel = null;
					}
				} catch (IOException e) {
					Log.e(TAG, "Exception while removing channel", e);
				}
				mApplicationStarted = false;
			}
			if (mApiClient.isConnected()) {
				mApiClient.disconnect();
			}
			mApiClient = null;
		}
		mSelectedDevice = null;
		mWaitingForReconnect = false;
		hideMediaControlIcons();
	}

	/**
	 * Google Play services callbacks
	 */
	private class ConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {
		@Override
		public void onConnected(Bundle connectionHint) {
			Log.d(TAG, "onConnected");

			if (mApiClient == null) {
				// We got disconnected while this runnable was pending
				// execution.
				return;
			}

			try {
				if (mWaitingForReconnect) {
					mWaitingForReconnect = false;

					// Check if the receiver app is still running
					if ((connectionHint != null) && connectionHint.getBoolean(Cast.EXTRA_APP_NO_LONGER_RUNNING)) {
						Log.d(TAG, "App is no longer running");
						teardown();
					} else {
						// Re-create the custom message channel
						try {
							Cast.CastApi.setMessageReceivedCallbacks(mApiClient,
									mMicrogramCasterChannel.getNamespace(), mMicrogramCasterChannel);
						} catch (IOException e) {
							Log.e(TAG, "Exception while creating channel", e);
						}
					}
				} else {
					// Launch the receiver app
					Cast.CastApi.launchApplication(mApiClient, APP_NAME, false).setResultCallback(
							new ResultCallback<Cast.ApplicationConnectionResult>() {
								@Override
								public void onResult(ApplicationConnectionResult result) {
									Status status = result.getStatus();
									Log.d(TAG,
											"ApplicationConnectionResultCallback.onResult: statusCode"
													+ status.getStatusCode());
									if (status.isSuccess()) {
										ApplicationMetadata applicationMetadata = result.getApplicationMetadata();
										String sessionId = result.getSessionId();
										String applicationStatus = result.getApplicationStatus();
										boolean wasLaunched = result.getWasLaunched();
										Log.d(TAG, "application name: " + applicationMetadata.getName() + ", status: "
												+ applicationStatus + ", sessionId: " + sessionId + ", wasLaunched: "
												+ wasLaunched);
										mApplicationStarted = true;

										// Create the custom message
										// channel
										mMicrogramCasterChannel = new MicrogramCasterChannel(MainActivity.this);
										try {
											Cast.CastApi.setMessageReceivedCallbacks(mApiClient,
													mMicrogramCasterChannel.getNamespace(), mMicrogramCasterChannel);
										} catch (IOException e) {
											Log.e(TAG, "Exception while creating channel", e);
										}

										// set the initial instructions
										// on the receiver
										// sendMessage(getString(R.string.app_name));
									} else {
										Log.e(TAG, "application could not launch");
										teardown();
									}
								}
							});
				}
			} catch (Exception e) {
				Log.e(TAG, "Failed to launch application", e);
			}
		}

		@Override
		public void onConnectionSuspended(int cause) {
			Log.d(TAG, "onConnectionSuspended");
			mWaitingForReconnect = true;
			hideMediaControlIcons();
			hideSeekbar();
		}
	}

	private void sendMessage(Command cmd) {
		if (mSelectedDevice == null || mApiClient == null || (mApiClient != null && !mApiClient.isConnected())) {
			if (mApiClient != null && mApiClient.isConnecting()) {
				Toast.makeText(MainActivity.this,
						"Currently connecting to Cast Device, please try again in a moment...", Toast.LENGTH_LONG)
						.show();
			} else {
				Toast.makeText(MainActivity.this,
						"Cast Device not connected, please try to disconnect and connect again", Toast.LENGTH_LONG)
						.show();
			}

			return;
		}
		try {
			JSONObject obj = new JSONObject();
			obj.put("id", cmd.getId());
			obj.put("params", new JSONObject(cmd.getParams()));

			if (mApiClient != null && mMicrogramCasterChannel != null) {
				try {
					Cast.CastApi.sendMessage(mApiClient, mMicrogramCasterChannel.getNamespace(), obj.toString());
				} catch (Exception e) {
					Log.e(TAG, "Exception while sending message", e);
				}
			} else {
				Toast.makeText(MainActivity.this, "Unable to send CMD to receiver, no connection", Toast.LENGTH_SHORT)
						.show();
				launchReceiver();
			}
		} catch (JSONException e) {
			Toast.makeText(MainActivity.this, "Unable to serialize CMD into JSON: " + e.getMessage(), Toast.LENGTH_LONG)
					.show();
		}
	}

	private String buildMediaItemURL(String fileName) {
		return MyHTTPD.WEB_SERVER_PROTOCOL + "://" + WifiHelper.getLanIP(MainActivity.this) + ":"
				+ MyHTTPD.WEB_SERVER_PORT + "/" + fileName;
	}

	/**
	 * Google Play services callbacks
	 */
	private class ConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {
		@Override
		public void onConnectionFailed(ConnectionResult result) {
			Log.e(TAG, "onConnectionFailed ");

			teardown();
		}
	}
}
