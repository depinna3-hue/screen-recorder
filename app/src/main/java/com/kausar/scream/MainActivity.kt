package com.kausar.scream

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var pageLabel: TextView
    private lateinit var pager: ViewPager2

    private val tabTitles = arrayOf("Layar", "Suara", "Daftar")
    private val pageHints = arrayOf(
        "Halaman 1 · Perekam layar (MP4)",
        "Halaman 2 · Perekam suara (AAC / WAV)",
        "Halaman 3 · Daftar rekaman (putar, lanjutkan)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F0F2F5"))
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#075E54"))
                setPadding(dp(16), dp(18), dp(16), dp(12))
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Header aplikasi Kausar Recorder"
            }
            header.addView(TextView(this).apply {
                text = "Kausar Recorder"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                contentDescription = "Judul aplikasi Kausar Recorder"
            })
            pageLabel = TextView(this).apply {
                text = pageHints[0]
                textSize = 13f
                setTextColor(Color.parseColor("#D1F4DE"))
                setPadding(0, dp(4), 0, 0)
                contentDescription = pageHints[0]
            }
            header.addView(pageLabel)
            root.addView(header)

            pager = ViewPager2(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                adapter = object : FragmentStateAdapter(this@MainActivity) {
                    override fun getItemCount() = 3
                    override fun createFragment(position: Int): Fragment = when (position) {
                        0 -> ScreenRecordFragment()
                        1 -> AudioRecordFragment()
                        else -> RecordingsFragment()
                    }
                }
                offscreenPageLimit = 1
            }
            root.addView(pager)

            val tabs = TabLayout(this).apply {
                tabMode = TabLayout.MODE_FIXED
                tabGravity = TabLayout.GRAVITY_FILL
                setBackgroundColor(Color.WHITE)
                setSelectedTabIndicatorColor(Color.parseColor("#075E54"))
                setTabTextColors(Color.parseColor("#667781"), Color.parseColor("#075E54"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(tabs)

            TabLayoutMediator(tabs, pager) { tab, pos ->
                tab.text = tabTitles[pos]
                tab.contentDescription = "Tab ${tabTitles[pos]}"
            }.attach()

            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    pageLabel.text = pageHints[position]
                    pageLabel.contentDescription = pageHints[position]
                }
            })

            setContentView(root)
            requestAllPermissions()
        } catch (t: Throwable) {
            val tv = TextView(this)
            tv.text = "Gagal membuka UI:\n${t.message}"
            tv.setPadding(32, 32, 32, 32)
            setContentView(tv)
            Toast.makeText(this, "Error: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAllPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            if (Build.VERSION.SDK_INT >= 23) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= 28) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 991)
        }
        RequestPermission.minta(this)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
