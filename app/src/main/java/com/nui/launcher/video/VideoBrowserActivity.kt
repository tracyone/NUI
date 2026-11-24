package com.nui.launcher.video

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.nui.launcher.NuiToast
import com.nui.launcher.R
import com.nui.launcher.databinding.ActivityVideoBrowserBinding
import java.io.File

/**
 * 视频文件浏览器（QuickTime「打开文件」风格）：浏览内置存储与 U盘/SD卡 等外接存储，
 * 点目录进入、点视频选中。选中后把路径写入 [VideoHost.pendingOpenPath] 并回到桌面，
 * 由 MainActivity 在音乐窗口启动播放。
 */
class VideoBrowserActivity : Activity() {

    private lateinit var binding: ActivityVideoBrowserBinding
    private var currentDir: File? = null
    private val storages = mutableListOf<File>()
    private var adapter: FileAdapter? = null

    companion object {
        private const val REQ_PERMISSION = 3001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnVideoBack.setOnClickListener { goUp() }

        if (ensurePermission()) {
            initStorages()
        } else {
            requestPermission()
        }
    }

    private fun initStorages() {
        storages.clear()
        storages.addAll(listStorages())
        buildStorageBar()

        val start = storages.firstOrNull() ?: Environment.getExternalStorageDirectory()
        if (start != null && start.exists()) openDir(start)
    }

    private fun ensurePermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perm, REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            if (ensurePermission()) {
                initStorages()
            } else {
                NuiToast.show(this, "需要存储权限才能浏览视频", android.widget.Toast.LENGTH_SHORT)
                finish()
            }
        }
    }

    // ==================== 存储枚举 ====================

    /** 枚举可浏览的存储：内置存储 + /storage 下的其它挂载点（U盘/SD卡）。 */
    private fun listStorages(): List<File> {
        val result = linkedSetOf<File>()
        runCatching {
            val primary = Environment.getExternalStorageDirectory()
            if (primary.exists()) result.add(primary)
            // 外接存储挂载点（形如 /storage/XXXX-XXXX）
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.canRead() && f.absolutePath != primary.absolutePath) {
                    result.add(f)
                }
            }
        }
        // 兜底：/mnt 下也可能有挂载
        runCatching {
            File("/mnt").listFiles()?.forEach { m ->
                m.listFiles()?.forEach { f ->
                    if (f.isDirectory && f.canRead() && f.absolutePath != "/mnt/sdcard") {
                        result.add(f)
                    }
                }
            }
        }
        return result.filter { it.exists() && it.isDirectory }
    }

    private fun storageLabel(f: File): String {
        val primary = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
        if (f.absolutePath == primary) return "内部存储"
        // 外接存储：用挂载点名（去掉 /storage/ 前缀）
        val name = f.name.ifBlank { f.absolutePath }
        return name
    }

    private fun buildStorageBar() {
        val bar = binding.videoStorageBar
        bar.removeAllViews()
        storages.forEach { s ->
            val tv = TextView(this).apply {
                text = storageLabel(s)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = getDrawable(R.drawable.bg_minus_ctrl)
                setOnClickListener { openDir(s) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(10) }
            bar.addView(tv, lp)
        }
    }

    // ==================== 目录导航 ====================

    private fun openDir(dir: File) {
        if (!dir.exists() || !dir.isDirectory) {
            NuiToast.show(this, "无法访问该目录", android.widget.Toast.LENGTH_SHORT)
            return
        }
        currentDir = dir
        binding.videoPathLabel.text = dir.absolutePath

        val items = runCatching { dir.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())
        // 目录在前、视频文件在后，各自按名排序
        val dirs = items.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val videos = VideoHost.listVideos(dir).sortedBy { it.name.lowercase() }
        val all = dirs + videos

        adapter = FileAdapter(this, all)
        binding.videoFileList.adapter = adapter
    }

    private fun goUp() {
        val parent = currentDir?.parentFile
        if (parent != null && parent.canRead()) {
            openDir(parent)
        } else {
            finish()
        }
    }

    private fun onSelect(file: File) {
        if (file.isDirectory) {
            openDir(file)
        } else {
            // 选中视频：写入待播放路径，回桌面由 MainActivity 播放
            VideoHost.pendingOpenPath = file.absolutePath
            finish()
        }
    }

    override fun onBackPressed() {
        goUp()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 列表 adapter ====================

    private inner class FileAdapter(
        private val ctx: Context,
        private val files: List<File>,
    ) : BaseAdapter() {

        override fun getCount(): Int = files.size
        override fun getItem(position: Int): Any = files[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val icon: ImageView
            val name: TextView
            val meta: TextView
            if (convertView != null) {
                view = convertView
                icon = view.findViewById(R.id.videoFileIcon)
                name = view.findViewById(R.id.videoFileName)
                meta = view.findViewById(R.id.videoFileMeta)
            } else {
                view = LayoutInflater.from(ctx).inflate(R.layout.item_video_file, parent, false)
                icon = view.findViewById(R.id.videoFileIcon)
                name = view.findViewById(R.id.videoFileName)
                meta = view.findViewById(R.id.videoFileMeta)
            }
            val f = files[position]
            if (f.isDirectory) {
                icon.setImageResource(R.drawable.ic_folder)
                name.text = f.name
                meta.text = "目录"
            } else {
                icon.setImageResource(R.drawable.ic_video)
                name.text = f.name
                meta.text = formatSize(f.length())
            }
            view.setOnClickListener { onSelect(f) }
            return view
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
