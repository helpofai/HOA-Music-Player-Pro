import os

replacements = {
    # Classes and Files
    "RetroShapeableImageView": "HoaShapeableImageView",
    "RetroUtil": "HoaUtil",
    "RetroColorUtil": "HoaColorUtil",
    "RetroGlideExtension": "HoaGlideExtension",
    
    # Attributes
    "retroCornerSize": "hoaCornerSize",
    
    # Themes (XML and Code references)
    "Theme.RetroMusic": "Theme.HOAMusic",
    "Theme_RetroMusic": "Theme_HOAMusic",
    
    # String Content (UI)
    "Retro Music": "HOA Music",
    "Retro music": "HOA Music",
    "retro music": "HOA Music"
}

# Directories to skip
ignore_dirs = {'.git', '.gradle', 'build', '.idea', 'gradle'}
# Extensions to process
extensions = ('.java', '.kt', '.xml', '.gradle', '.kts', '.pro', '.html')

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
    dirs[:] = [d for d in dirs if d not in ignore_dirs]
    for file in files:
        if file.endswith(extensions):
            process_file(os.path.join(root, file))
