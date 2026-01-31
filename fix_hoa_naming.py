import os

replacements = {
    "hoaMusicColoredTarget": "HoaMusicColoredTarget",
    "hoaMusicGlideModule": "HoaMusicGlideModule"
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
