## **Q: How do I get the beta version of HOA Music Player?**
You can opt-in for the beta build by clicking on this link: https://play.google.com/apps/testing/com.helpofai.hoa.musicplayer

___

## **Q: How to restore my purchases?**
Make sure to switch and use your account in the Play Store app through which you purchased before installing HOA Music Player. The Google account used to install the app is also used to purchase/restore the pro license.

If you've already installed the app, remove all other accounts except the one from which you purchased premium, and then restore the purchase.

___

## **Q: How do I use offline synced lyrics?**
There are three methods for adding offline synced lyrics in HOA Music Player.

### ***Method 1:-***
#### STEP 1: 
Find the time-stamped lyrics for your songs that don't have lyrics already. A time-stamped lyric looks like this, "[00:04:02] Some lyrics text" for example.
#### STEP 2: 
Copy these time-stamped lyrics.
#### STEP 3: 
Open HOA Music Player and head to the song synced lyrics editor.
#### STEP 4: 
Paste the lyrics there normally and exit the editor
#### STEP 5: 
Open lyrics and you should see your time-stamped lyrics there.

### ***Method 2:-***
#### STEP 1: 
Download the time-stamped lyrics for your songs that don't have lyrics already. You can find ".lrc" files for popular songs.
#### STEP 2: 
A ".lrc" is like a text file that contains the time-stamped lyrics for example, "[00:04:02] Some lyrics text". Save your time-stamped lyrics as ".lrc" file.
#### STEP 3: 
Now you have to rename the file you created in this way: <song_name> - <artist_name>.lrc or for better matching copy the <song_name> and the <artist_name> from the tag editor and then rename the file.
### STEP 4: 
Now paste the LRC files to the following path: /sdcard/HOAMusic/lyrics/
Here sdcard is your internal storage.

### ***Method 3:- (Requires third-party app)***
#### STEP 1:
Download automatag or autotagger from Play Store.
#### STEP 2:
Find the time-stamped lyrics for your songs that don't have lyrics already.
#### STEP 3:
Find your song to edit and paste the synced lyrics. 
> These apps add those synced lyrics in the music file itself instead of creating a ".lrc file for it."

___

## **Q: How do I change the theme?**
Settings -> Look and feel -> Select your theme.
HOA Music Player Pro supports **Material You** (Android 12+) and offers extended color palettes for deep personalization.

___

## **Q: What about the Equalizer?**
HOA Music Player Pro features a custom **High-Res Audio Engine**.
Instead of a standard equalizer, we provide professional DSP effects:
- **Stereo Balance & Width**
- **Diamond Clarity** (Vocal & Air enhancement)
- **Bass Boost** (Analog-style with soft clipping)
- **Room Effect** (Reverb)

Go to **Settings > Audio** to configure these. You can also enable **High-Res Audio** for 32-bit floating-point output.

___

## **Q: Why aren't last added songs showing?**
Settings -> Other -> Last added playlist interval -> Select an option from the list.
___

## **Q: How do I enable fullscreen lock screen controls?**
Settings -> Personalize -> Fullscreen controls -> Enable (this will only be visible when songs are playing).
___

## **Q: Why are my gallery or random pictures showing up as album art?**
Settings -> Images -> Ignore media store covers -> Enable
___

## **Q: Which file types are supported?**
HOA Music Player uses the native media player that comes with your Android phone, so as long as a file type is supported by your phone, it's supported by us.
___

## **Q: Why is my device slowing down when I'm using the app?**
HOA Music Player is image intensive, it keeps images in the cache for quick loading.
___

## **Q: The title "HOA Music Player" is showing on the top of the app, how can I fix this?** 
Clear the app's cache and data.
___

## **Q: My app is crashing, how do I fix this?** (Sorry, settings have changed internally) 
Please try to clear the data of the app. If it doesn't work, reinstalling fresh from the play store should help.
___

## **Q: Why has all the text gone white/disappeared?** 
Change the theme to Black or Dark and change it back to what you had before.
___

## **Q: Why some of my songs are not showing in my library?**
- Try checking up if those songs are not less than 30 seconds, if so head to settings -> other -> filter song duration. Put this to zero and see the songs that should start appearing in the library.

- If this doesn't work out for you, re-scanning the media folder should help and subsequently rebooting the device to refresh the media store.

- At last, resort, If nothing worked and your audio files are stored in SD card. Try moving them to internal memory then back to SD card.

___

## **Q: Why does my library shows song files twice or no song at all?**
If you are seeing duplication of songs in the library or no songs at all, then it's because of the Media Store issue which got affected by some other app. 

***To fix this:***
1. Head to your device settings

1. Open up "Apps & notifications" (This name depends from ROM to ROM)

1. Find the 'Media storage' app and clear storage (both data and cache) of it.

1. Then open the HOA Music Player app and manually scan your music from your storage. 

1. Reboot the device to refresh the media store (Not sure if this is necessary)

**NOTE:** Don't panic when you will open HOA Music Player and see "Zero" songs there in the library. It's because you cleared Media Store which is responsible for recognising files on your device.
___

## **Q: I can't find the folder menu anymore after the latest update?**
Head to settings -> personalise. And select folders from "library categories". If there is no option of folders, tap on reset and select folders.
___

## **Q: After updating the app to the latest version, the font got removed. Why?**
- The font has now been replaced with system font, which means the default font your system uses will be used by HOA Music Player too. It fixes all font-related issues you used to face/are facing in the app. 

- You can toggle "Use Manrope font" from Settings > Look & Feel.

- If you think the font looks ugly, then you just need to change the default font from your Android settings (or use any Magisk module). If you can't, there's nothing we can do about it.
___

## **Q: How to export playlist?**
- ***From HOA Music Player:***

Head to the playlists tab > tap on the three-dot menu on the playlist you want to export > save as a file.

- ***From Other Music Players:***

In your built-in music player, there should be an option to save that playlist as a file. Save them and import them from the file manager by opening it into HOA Music Player.

> Note that such playlist must be of your offline music only since HOA Music Player is an offline music player, not an online music player. So if your playlist is of online music, it can't be opened on other offline players nor can be exported
___

## **Q: How to restore/import playlist?**
HOA Music Player will automatically detect any playlist file when that playlist file is stored in internal storage/Playlist. However, if it doesn't, just open any "File manager" and open that playlist file with HOA Music Player.

For restoring playlists successfully, the location of songs must be the same in both the "Playlist" file and in your storage. For example, If your music is in "Internal storage/Music" and the playlist file has songs location "Internal storage/Songs". Then it will not be going to work since both these locations are different.
