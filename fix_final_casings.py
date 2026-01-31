import os

replacements = {
    # Fix Retrofit lowercasing
    "import retrofit2.retrofit": "import retrofit2.Retrofit",
    ": retrofit": ": Retrofit",
    "p2: retrofit": "p2: Retrofit",
    "p3: retrofit": "p3: Retrofit",
    "retrofit.Builder": "Retrofit.Builder",
    "provideLastFmretrofit": "provideLastFmRetrofit",
    
    # Fix HOA lowercasing and renaming
    "hoaDatabase": "HoaDatabase",
    "hoaWebServer": "HoaWebServer",
    "hoaSessionManagerListener": "HoaSessionManagerListener",
    "RetroDatabase": "HoaDatabase",
    "RetroWebServer": "HoaWebServer",
    "RetroSessionManagerListener": "HoaSessionManagerListener",
    
    # Fix ID casing
    "cardhoaInfo": "cardHoaInfo",
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
