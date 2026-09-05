package com.rentpoly.game

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.rentpoly.game.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonNew.setOnClickListener {
            val bots = when (binding.groupBots.checkedRadioButtonId) {
                binding.bots2.id -> 2
                binding.bots3.id -> 3
                else -> 1
            }
            startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_BOTS, bots))
        }
        binding.buttonContinue.setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_CONTINUE, true))
        }
    }

    override fun onResume() {
        super.onResume()
        binding.buttonContinue.visibility = if (GameActivity.hasSave(this)) View.VISIBLE else View.GONE
    }
}
