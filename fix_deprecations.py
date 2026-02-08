import os

replacements = {
    # Fix ExoPlayer class name and usage
    "class hoaExoPlayer": "class HoaExoPlayer",
    "hoaExoPlayer": "HoaExoPlayer",
    "RetroExoPlayer": "HoaExoPlayer", # In case I missed some
    
    # Fix DefaultAudioSink deprecation
    "DefaultAudioSink.Builder()": "DefaultAudioSink.Builder(context)",
    
    # Fix Activity.parent deprecation in MusicPlayerRemote
    "val realActivity = (context as Activity).parent ?: context": "val realActivity = context",
    
    # Fix window.navigationBarColor deprecation (specific to AbsSlidingMusicPanelActivity)
    ".ofArgb(window.navigationBarColor, color)": ".ofArgb(navigationBarColor, color)"
}

# Extensions to process
extensions = ('.java', '.kt')

def process_file(filepath):
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
        
        new_content = content
        for old, new in replacements.items():
            new_content = new_content.replace(old, new)
        
        if content != new_content:
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(new_content)
            print(f"Updated: {filepath}")
    except Exception as e:
        print(f"Skipping {filepath}: {e}")

for root, dirs, files in os.walk("."):
    if 'build' in dirs:
        dirs.remove('build')
    for file in files:
        if file.endswith(extensions):
            process_file(os.path.join(root, file))
