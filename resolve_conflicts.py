import os

def resolve_file(filepath, resolver_func):
    if not os.path.exists(filepath):
        print(f"File not found: {filepath}")
        return
        
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
        
    if '<<<<<<< HEAD' not in content:
        print(f"No conflicts in {filepath}")
        return
        
    parts = []
    lines = content.split('\n')
    i = 0
    while i < len(lines):
        if lines[i].startswith('<<<<<<< HEAD'):
            head_block = []
            i += 1
            while not lines[i].startswith('======='):
                head_block.append(lines[i])
                i += 1
            i += 1
            feature_block = []
            while not lines[i].startswith('>>>>>>>'):
                feature_block.append(lines[i])
                i += 1
            i += 1 # skip >>>>>>>
            
            resolved_text = resolver_func(filepath, '\n'.join(head_block), '\n'.join(feature_block))
            if resolved_text is not None:
                parts.append(resolved_text)
            else:
                parts.append('<<<<<<< HEAD\n' + '\n'.join(head_block) + '\n=======\n' + '\n'.join(feature_block) + '\n>>>>>>> Normal-Feature')
        else:
            parts.append(lines[i])
            i += 1
            
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write('\n'.join(parts))
    print(f"Resolved {filepath}")

def my_resolver(filepath, head, feature):
    filename = os.path.basename(filepath)
    
    if filename == "BasicActivity.kt" or filename == "ManualActivity.kt":
        if "imagesSavingCount" in head and "videoRecordingStartTime" in feature:
            return head + '\n' + feature
        if "imagesSavingCount > 0" in head and "album_progress" in feature:
            return """            if (imagesSavingCount > 0) {
                val albumProgress = findViewById<android.view.View>(R.id.album_progress)
                albumProgress?.visibility = android.view.View.VISIBLE
                android.widget.Toast.makeText(this, "Đang xử lý ảnh...", android.widget.Toast.LENGTH_SHORT).show()"""

    if filename == "CameraManagerHelper.kt":
        # Usually, Normal-Feature has the most robust fixes. But wait, I shouldn't blindly accept Normal-Feature if HEAD has good stuff. 
        # Actually I can just return feature (Normal-Feature) which has my optimizations + the LUT functions.
        return feature
        
    if filename == "GlVideoProcessor.kt" or filename == "EglCore.kt" or filename == "GlLutFilter.kt":
        return feature
        
    if filename == "activity_basic.xml" or filename == "activity_manual.xml":
        if "CircularProgressIndicator" in head and "ImageView" in feature:
            # Combine the views. CircularProgressIndicator instead of ProgressBar, keep ImageView.
            res = feature.replace("<ProgressBar", "<!--ProgressBar").replace("/>\n>>>>>>> Normal-Feature", "/>-->") 
            # Note: feature doesn't have >>>>>>> Normal-Feature.
            # We'll just append HEAD to the bottom, which has CircularProgressIndicator.
            return feature + "\n" + head
        if "exposure_countdown" in head and "tv_countdown" in feature:
            return head + "\n" + feature
            
    if filename == "item_lut.xml":
        return feature

    return feature # Default to feature

resolve_file('app/src/main/java/com/example/optik/BasicActivity.kt', my_resolver)
resolve_file('app/src/main/java/com/example/optik/ManualActivity.kt', my_resolver)
resolve_file('app/src/main/java/com/example/optik/camera/CameraManagerHelper.kt', my_resolver)
resolve_file('app/src/main/java/com/example/optik/camera/GlVideoProcessor.kt', my_resolver)
resolve_file('app/src/main/java/com/example/optik/camera/gl/EglCore.kt', my_resolver)
resolve_file('app/src/main/java/com/example/optik/camera/gl/GlLutFilter.kt', my_resolver)
resolve_file('app/src/main/res/layout/activity_basic.xml', my_resolver)
resolve_file('app/src/main/res/layout/activity_manual.xml', my_resolver)
resolve_file('app/src/main/res/layout/item_lut.xml', my_resolver)
