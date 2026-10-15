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

class MainActivity : AppCompatActivity() {

    private lateinit var pageLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F3F6FB"))
                setPadding(dp(12), dp(16), dp(12), dp(12))
            }

            root.addView(TextView(this).apply {
                text = "Kausar Screen & Voice Recorder"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#111827"))
                gravity = Gravity.CENTER
            })

            root.addView(TextView(this).apply {
                text = "Geser kiri / kanan untuk ganti halaman"
                textSize = 13f
                setTextColor(Color.parseColor("#6B7280"))
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(10))
            })

            val pager = ViewPager2(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                adapter = object : FragmentStateAdapter(this@MainActivity) {
                    override fun getItemCount() = 2
                    override fun createFragment(position: Int): Fragment =
                        if (position == 0) ScreenRecordFragment() else AudioRecordFragment()
                }
            }
            root.addView(pager)

            pageLabel = TextView(this).apply {
                text = "Halaman 1 · Perekam layar (MP4)"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1565C0"))
                setPadding(0, dp(8), 0, 0)
            }
            root.addView(pageLabel)

            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    pageLabel.text = if (position == 0)
                        "Halaman 1 · Perekam layar (MP4)"
                    else
                        "Halaman 2 · Perekam suara (AAC / MP3 / WAV)"
                }
            })

            setContentView(root)
            requestPerms()
        } catch (t: Throwable) {
            val tv = TextView(this)
            tv.text = "Gagal membuka UI:\n${t.message}"
            tv.setPadding(32, 32, 32, 32)
            setContentView(tv)
            Toast.makeText(this, "Error: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPerms() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 991)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}