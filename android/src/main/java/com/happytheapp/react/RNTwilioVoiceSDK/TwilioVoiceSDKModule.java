package com.happytheapp.react.RNTwilioVoiceSDK;

import android.media.AudioManager;

import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableMap;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import com.twilio.audioswitch.AudioDevice;
import com.twilio.audioswitch.AudioSwitch;
import com.twilio.audioswitch.AudioDevice.Speakerphone;
import com.twilio.audioswitch.AudioDevice.BluetoothHeadset;
import com.twilio.audioswitch.AudioDevice.WiredHeadset;
import com.twilio.audioswitch.AudioDevice.Earpiece;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.LogLevel;
import com.twilio.voice.Voice;

import android.media.MediaPlayer;
import android.content.res.AssetFileDescriptor;
import java.io.IOException;
import java.io.File;

import java.util.HashMap;
import java.util.List;

import static com.happytheapp.react.RNTwilioVoiceSDK.EventManager.EVENT_RINGING;
import static com.happytheapp.react.RNTwilioVoiceSDK.EventManager.EVENT_CONNECTED;
import static com.happytheapp.react.RNTwilioVoiceSDK.EventManager.EVENT_CONNECT_FAILURE;
import static com.happytheapp.react.RNTwilioVoiceSDK.EventManager.EVENT_RECONNECTING;
import static com.happytheapp.react.RNTwilioVoiceSDK.EventManager.EVENT_RECONNECTED;
import static com.happytheapp.react.RNTwilioVoiceSDK.EventManager.EVENT_DISCONNECTED;

public class TwilioVoiceSDKModule extends ReactContextBaseJavaModule
        implements LifecycleEventListener {

    public static String TAG = "RNTwilioVoiceSDK";

    ReactApplicationContext context;
    private Call.Listener callListener = callListener();
    private BluetoothHeadset mBluetoothHeadset;
    private Boolean headsetConnected = false;
    private Boolean speaker = false;
    private Call activeCall;
    private ProximityManager proximityManager;
    private EventManager eventManager;
    protected MediaPlayer mediaPlayer;
    private AudioSwitch audioSwitch;

    public TwilioVoiceSDKModule(ReactApplicationContext reactContext) {
        super(reactContext);
        if (BuildConfig.DEBUG) {
            Voice.setLogLevel(LogLevel.DEBUG);
        } else {
            Voice.setLogLevel(LogLevel.ERROR);
        }
        reactContext.addLifecycleEventListener(this);
        context = reactContext;
        eventManager = new EventManager(reactContext);
        proximityManager = new ProximityManager(reactContext);
        audioSwitch = new AudioSwitch(reactContext);
        audioSwitch.start(new Function2<List<? extends AudioDevice>, AudioDevice, Unit>() {
            @Override
            public Unit invoke(List<? extends AudioDevice> audioDevices, AudioDevice audioDevice) {
                return Unit.INSTANCE;
            }
        });
    }

    // region Lifecycle Event Listener
    @Override
    public void onHostResume() {
        Log.d("TZ", "HOST RESUME");
        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        if (getCurrentActivity() != null) {
            getCurrentActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        }
    }

    @Override
    public void onHostPause() {
        // the library needs to listen for events even when the app is paused
        // unregisterReceiver();
    }

    @Override
    public void onHostDestroy() {
        Log.d("TZ", "HOST DESTROY");
        disconnect();
        audioSwitch.stop();
    }
    // endregion

    @Override
    public String getName() {
        return TAG;
    }

    private Call.Listener callListener() {
        return new Call.Listener() {
            @Override
            public void onConnected(@NonNull Call call) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "CALL CONNECTED callListener().onConnected call state = " + call.getState());
                }
                activeCall = call;
                eventManager.sendEvent(EVENT_CONNECTED, paramsFromCall(call));

                mediaPlayer.pause();
                mediaPlayer.seekTo(0);
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException error) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "reconnecting");
                }
                activeCall = call;
                eventManager.sendEvent(EVENT_RECONNECTING, paramsWithError(call, error));
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "reconnected");
                }
                activeCall = call;
                eventManager.sendEvent(EVENT_RECONNECTED, paramsFromCall(call));
            }

            @Override
            public void onDisconnected(@NonNull Call call, CallException error) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "call disconnected");
                }
                activeCall = call;
                eventManager.sendEvent(EVENT_DISCONNECTED, paramsWithError(call, error));
                call.disconnect();
                activeCall = null;
            }

            @Override
            public void onConnectFailure(@NonNull Call call, @NonNull CallException error) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "connect failure");
                }
                activeCall = call;
                mediaPlayer.pause();
                mediaPlayer.seekTo(0);
                audioSwitch.deactivate();
                WritableMap params = paramsWithError(call, error);
                call.disconnect();
                activeCall = null;
                eventManager.sendEvent(EVENT_CONNECT_FAILURE, params);
            }

            @Override
            public void onRinging(@NonNull Call call) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "ringing");
                }
                activeCall = call;
                eventManager.sendEvent(EVENT_RINGING, paramsFromCall(call));
            }
        };
    }

    protected MediaPlayer createMediaPlayer(final String fileName) {
        int res = this.context.getResources().getIdentifier(fileName, "raw", this.context.getPackageName());
        MediaPlayer mediaPlayer = new MediaPlayer();
        if (res != 0) {
            try {
                AssetFileDescriptor afd = this.context.getResources().openRawResourceFd(res);
                mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            } catch (IOException e) {
                Log.e("RNSoundModule", "Exception", e);
                return null;
            }
            return mediaPlayer;
        }

        if (fileName.startsWith("http://") || fileName.startsWith("https://")) {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            Log.i("RNSoundModule", fileName);
            try {
                mediaPlayer.setDataSource(fileName);
            } catch (IOException e) {
                Log.e("RNSoundModule", "Exception", e);
                return null;
            }
            return mediaPlayer;
        }

        if (fileName.startsWith("asset:/")) {
            try {
                AssetFileDescriptor descriptor = this.context.getAssets().openFd(fileName.replace("asset:/", ""));
                mediaPlayer.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(),
                        descriptor.getLength());
                descriptor.close();
                return mediaPlayer;
            } catch (IOException e) {
                Log.e("RNSoundModule", "Exception", e);
                return null;
            }
        }

        if (fileName.startsWith("file:/")) {
            try {
                mediaPlayer.setDataSource(fileName);
            } catch (IOException e) {
                Log.e("RNSoundModule", "Exception", e);
                return null;
            }
            return mediaPlayer;
        }

        File file = new File(fileName);
        if (file.exists()) {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            Log.i("RNSoundModule", fileName);
            try {
                mediaPlayer.setDataSource(fileName);
            } catch (IOException e) {
                Log.e("RNSoundModule", "Exception", e);
                return null;
            }
            return mediaPlayer;
        }

        return null;
    }

    @ReactMethod
    public void connect(final String accessToken, ReadableMap params, Promise promise) {
        speaker = false;

        this.mediaPlayer = this.createMediaPlayer("dial_2");
        try {
            this.mediaPlayer.prepare();
        } catch (Exception ignored) {
            // When loading files from a file, we useMediaPlayer.create, which actually
            // prepares the audio for us already. So we catch and ignore this error
            Log.e("RNSoundModule", "Exception", ignored);
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "connect params: " + params);
        }
        if (activeCall != null) {
            promise.reject("already_connected", "Calling connect while a call is connected");
        }

        // Enable proximity monitoring
        proximityManager.startProximitySensor();

        // create parameters for call
        HashMap<String, String> twiMLParams = new HashMap<>();
        ReadableMapKeySetIterator iterator = params.keySetIterator();
        while (iterator.hasNextKey()) {
            String key = iterator.nextKey();
            ReadableType readableType = params.getType(key);
            switch (readableType) {
                case Null:
                    twiMLParams.put(key, "");
                    break;
                case Boolean:
                    twiMLParams.put(key, String.valueOf(params.getBoolean(key)));
                    break;
                case Number:
                    // Can be int or double.
                    twiMLParams.put(key, String.valueOf(params.getDouble(key)));
                    break;
                case String:
                    twiMLParams.put(key, params.getString(key));
                    break;
                default:
                    Log.d(TAG, "Could not convert with key: " + key + ".");
                    break;
            }
        }

        ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                .params(twiMLParams)
                .build();
        activeCall = Voice.connect(getReactApplicationContext(), connectOptions, callListener);
        if (activeCall != null) {
            this.mediaPlayer.setLooping(true);
            this.mediaPlayer.start();
        }
        audioSwitch.activate();
        deriveAudioOutputSource();
        promise.resolve(paramsFromCall(activeCall));
    }

    @ReactMethod
    public void disconnect() {
        if (activeCall != null) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
            audioSwitch.deactivate();
            setMuted(false);
            proximityManager.stopProximitySensor();
            activeCall.disconnect();
            activeCall = null;
        }
    }

    @ReactMethod
    public void setMuted(Boolean muteValue) {
        if (activeCall != null) {
            activeCall.mute(muteValue);
        }
    }

    @ReactMethod
    public void sendDigits(String digits) {
        if (activeCall != null) {
            activeCall.sendDigits(digits);
        }
    }

    @ReactMethod
    public void getVersion(Promise promise) {
        promise.resolve(Voice.getVersion());
    }

    @ReactMethod
    public void getActiveCall(Promise promise) {
        if (activeCall != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Active call found state = " + activeCall.getState());
            }
            promise.resolve(paramsFromCall(activeCall));
            return;
        }
        promise.reject("no_call", "There was no active call");
    }

    private void deriveAudioOutputSource() {
        if(speaker) {
            Log.d("TZ", "SWITCHING TO SPEAKER");
            audioSwitch.selectDevice(new Speakerphone());
        } else {
            Log.d("TZ", "SWITCHING TO DIFFERENT OUTPUT");
            List<AudioDevice> devices = audioSwitch.getAvailableAudioDevices();
            Boolean bt = false;
            Boolean wired = false;
    
            for(AudioDevice dev : devices){
                if (dev instanceof BluetoothHeadset) {
                    bt = true;
                } else if(dev instanceof WiredHeadset) {
                    wired = true;
                }
            }
    
            if(bt) {
                audioSwitch.selectDevice(new BluetoothHeadset());
            } else if(wired) {
                audioSwitch.selectDevice(new WiredHeadset());
            } else {
                audioSwitch.selectDevice(new Earpiece());
            }
        }
    }

    @ReactMethod
    public void setSpeakerPhone(Boolean value) {
        speaker = value;
        deriveAudioOutputSource();
    }


    // region create JSObjects helpers
    private WritableMap paramsFromCall(Call call) {
        WritableMap params = Arguments.createMap();
        if (call != null) {
            if (call.getSid() != null) {
                params.putString("sid", call.getSid());
            }
            if (call.getFrom() != null) {
                params.putString("from", activeCall.getFrom());
            }
            if (call.getTo() != null) {
                params.putString("to", activeCall.getTo());
            }
            params.putString("state", call.getState().name());
        }
        return params;
    }

    private WritableMap paramsWithError(Call call, CallException error) {
        WritableMap params = paramsFromCall(call);
        if (error != null) {
            Log.e(TAG, String.format("CallListener onDisconnected error: %d, %s",
                    error.getErrorCode(), error.getMessage()));
            WritableMap errorParams = Arguments.createMap();
            errorParams.putInt("code", error.getErrorCode());
            errorParams.putString("message", error.getLocalizedMessage());
            params.putMap("error", errorParams);
        }
        return params;
    }
}
