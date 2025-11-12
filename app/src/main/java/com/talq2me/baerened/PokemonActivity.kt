package com.talq2me.baerened

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.max

class PokemonActivity : AppCompatActivity() {

    private lateinit var progressManager: DailyProgressManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var pokemonAdapter: PokemonAdapter
    private lateinit var recentlyUnlockedText: TextView
    private lateinit var adminButton: ImageButton
    private lateinit var backToTopButton: android.widget.Button
    private lateinit var headerLayout: LinearLayout

    private var pokemonList: List<Pokemon> = emptyList()
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pokemon)

        progressManager = DailyProgressManager(this)

        initializeViews()
        setupRecyclerView()
        loadPokemonData()
        setupScrollListener()
        setupBackToTopButton()
    }

    private fun initializeViews() {
        headerLayout = findViewById(R.id.headerLayout)
        recentlyUnlockedText = findViewById<TextView>(R.id.recentlyUnlockedText)
        adminButton = findViewById<ImageButton>(R.id.adminButton)
        backToTopButton = findViewById(R.id.backToTopButton) as android.widget.Button
        recyclerView = findViewById<RecyclerView>(R.id.pokemonRecyclerView)

        // Set up header buttons
        setupHeaderButtons()

        // Set up admin button (settings icon)
        adminButton.setOnClickListener {
            showAdminModal()
        }

        // Set up back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "My Pokédex"
    }

    private fun setupHeaderButtons() {
        headerLayout.removeAllViews()

        // Create navigation buttons similar to MainActivity
        val backButton = createHeaderButton("< Back", "back")
        val homeButton = createHeaderButton("⌂ Home", "home")
        val refreshButton = createHeaderButton("⟳ Refresh", "refresh")

        headerLayout.addView(backButton)
        headerLayout.addView(homeButton)
        headerLayout.addView(refreshButton)
    }

    private fun createHeaderButton(text: String, action: String): android.widget.Button {
        return android.widget.Button(this).apply {
            this.text = text
            textSize = 18f
            setOnClickListener {
                handleHeaderButtonClick(action)
            }

            // Style buttons to match MainActivity
            layoutParams = LinearLayout.LayoutParams(
                140.dpToPx(), // 140dp width
                70.dpToPx()  // 70dp height
            ).apply {
                marginEnd = 12.dpToPx()
            }

            background = resources.getDrawable(R.drawable.button_rounded)
            setTextColor(resources.getColor(android.R.color.white))
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
    }

    private fun handleHeaderButtonClick(action: String) {
        when (action) {
            "back" -> finish()
            "home" -> {
                // Navigate to main screen (home)
                val intent = android.content.Intent(this, MainActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
            "refresh" -> {
                // Refresh the Pokemon data
                loadPokemonData()
            }
        }
    }

    private fun setupRecyclerView() {
        // Use responsive grid layout - at least 5 columns, more on larger screens
        val spanCount = calculateOptimalSpanCount()
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)

        pokemonAdapter = PokemonAdapter(this, pokemonList) { pokemon ->
            // Handle Pokemon click if needed
            Log.d("PokemonActivity", "Clicked Pokemon: ${pokemon.name}")
        }

        recyclerView.adapter = pokemonAdapter

        // Ensure grid layout is properly set for current orientation
        updateGridLayoutForOrientation()
    }

    private fun calculateOptimalSpanCount(): Int {
        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val orientation = resources.configuration.orientation

        // Base calculation on screen width in dp
        val baseColumns = when {
            screenWidthDp >= 800 -> 6 // Large tablets
            screenWidthDp >= 600 -> 5 // Small tablets
            screenWidthDp >= 480 -> 4 // Large phones in landscape
            else -> 3 // Regular phones
        }

        // Adjust for orientation
        return when (orientation) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> baseColumns + 1
            else -> maxOf(baseColumns, 3) // At least 3 columns in portrait
        }
    }

    private fun setupScrollListener() {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val layoutManager = recyclerView.layoutManager as GridLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                // Show/hide back to top button
                if (firstVisibleItemPosition > 5) {
                    (backToTopButton as android.widget.Button).visibility = android.view.View.VISIBLE
                } else {
                    (backToTopButton as android.widget.Button).visibility = android.view.View.GONE
                }

                // Load more Pokemon if near end (lazy loading)
                if (!isLoading && (visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 5) {
                    loadMorePokemon()
                }
            }
        })
    }

    private fun setupBackToTopButton() {
        (backToTopButton as android.widget.Button).setOnClickListener {
            recyclerView.smoothScrollToPosition(0)
        }
    }

    private fun loadPokemonData() {
        isLoading = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val unlockedCount = progressManager.getUnlockedPokemonCount()
                val pokemon = loadPokemonFromAssets(unlockedCount)

                withContext(Dispatchers.Main) {
                    pokemonList = pokemon
                    pokemonAdapter.updatePokemon(pokemon)
                    updateRecentlyUnlocked()
                    isLoading = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading Pokemon data", e)
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    private fun loadMorePokemon() {
        // For now, just load all available Pokemon
        // In a real app, you might want to implement pagination
        if (pokemonList.size < getTotalAvailablePokemon()) {
            loadPokemonData()
        }
    }

    private fun loadPokemonFromAssets(maxUnlocked: Int): List<Pokemon> {
        val pokemon = mutableListOf<Pokemon>()
        val assetsPath = "images/pokeSprites/sprites/pokemon"

        try {
            val assetManager = assets
            val files = assetManager.list(assetsPath) ?: return emptyList()

            // Parse Pokemon files (format: "prefix-pokenum-variant.png")
            val pokemonFiles = files.filter { it.endsWith(".png") }
                .mapNotNull { parsePokemonFilename(it) }
                .filter { it.prefix <= maxUnlocked }
                .sortedWith(compareBy({ it.pokenum }, { it.shiny }))

            pokemonFiles.forEach { pokemonFile ->
                val imagePath = "$assetsPath/${pokemonFile.filename}"
                val pokemonObj = Pokemon(
                    id = pokemonFile.pokenum,
                    name = "#${pokemonFile.pokenum}${if (pokemonFile.shiny) " (Shiny)" else ""}",
                    imagePath = imagePath,
                    isShiny = pokemonFile.shiny,
                    number = pokemonFile.pokenum
                )
                pokemon.add(pokemonObj)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading Pokemon from assets", e)
        }

        return pokemon
    }

    private fun parsePokemonFilename(filename: String): PokemonFile? {
        // Remove .png extension
        val base = filename.removeSuffix(".png")
        // Check if it's shiny (ends with -s)
        val shiny = base.endsWith("-s")
        // Remove -s if present for parsing numbers
        val baseNoShiny = if (shiny) base.substringBeforeLast("-s") else base
        // Split by '-'
        val parts = baseNoShiny.split("-")

        if (parts.size < 2) return null

        val prefix = parts[0].toIntOrNull() ?: return null
        val pokenum = parts[1].toIntOrNull() ?: return null

        return PokemonFile(prefix, pokenum, shiny, filename)
    }

    private fun updateRecentlyUnlocked() {
        val unlockedCount = progressManager.getUnlockedPokemonCount()
        recentlyUnlockedText.text = "Unlocked Pokemon: $unlockedCount"

        // Show last unlocked Pokemon if available
        val lastPokemon = getLastUnlockedPokemon()
        if (lastPokemon != null) {
            // You could show a special highlight for the most recently unlocked Pokemon
        }
    }

    private fun getLastUnlockedPokemon(): Pokemon? {
        val unlockedCount = progressManager.getUnlockedPokemonCount()
        return pokemonList.lastOrNull { it.number <= unlockedCount }
    }

    private fun getTotalAvailablePokemon(): Int {
        // Count total Pokemon files in assets
        return try {
            val files = assets.list("images/pokeSprites/sprites/pokemon") ?: emptyArray()
            files.count { it.endsWith(".png") }
        } catch (e: Exception) {
            Log.e(TAG, "Error counting Pokemon files", e)
            1000 // Fallback estimate
        }
    }

    private fun showAdminModal() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_admin_pokemon, null)
        val pinInput = dialogView.findViewById<EditText>(R.id.adminPinInput)
        val unlockCountInput = dialogView.findViewById<EditText>(R.id.unlockCountInput)
        val adminMessage = dialogView.findViewById<TextView>(R.id.adminMessage)

        // Clear any previous error message
        adminMessage.text = ""
        adminMessage.visibility = android.view.View.GONE

        val dialog = AlertDialog.Builder(this)
            .setTitle("Admin Restore")
            .setView(dialogView)
            .setPositiveButton("Restore", null) // Set to null initially to prevent auto-close
            .setNegativeButton("Cancel") { _, _ ->
                // Do nothing
            }
            .create()

        // Set up click listener after dialog is created to prevent auto-close
        dialog.setOnShowListener {
            val restoreButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            restoreButton.setOnClickListener {
                val pin = pinInput.text.toString().trim()
                val countString = unlockCountInput.text.toString().trim()

                // Clear any previous error message
                adminMessage.text = ""
                adminMessage.visibility = android.view.View.GONE

                // Use SettingsManager.readPin() to match the rest of the app
                val correctPin = SettingsManager.readPin(this) ?: "1981" // Use default if not set
                Log.d(TAG, "Validating PIN. Entered: '$pin', Expected: '$correctPin'")
                
                if (pin.isEmpty()) {
                    adminMessage.text = "Please enter a PIN!"
                    adminMessage.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                
                if (pin != correctPin) {
                    adminMessage.text = "Incorrect PIN! Please try again."
                    adminMessage.visibility = android.view.View.VISIBLE
                    pinInput.text.clear()
                    pinInput.requestFocus()
                    return@setOnClickListener
                }
                
                // PIN is correct, now validate count
                if (countString.isEmpty()) {
                    adminMessage.text = "Please enter the number of Pokemon to unlock!"
                    adminMessage.visibility = android.view.View.VISIBLE
                    unlockCountInput.requestFocus()
                    return@setOnClickListener
                }
                
                val count = countString.toIntOrNull()
                if (count == null || count <= 0) {
                    adminMessage.text = "Please enter a valid number greater than 0!"
                    adminMessage.visibility = android.view.View.VISIBLE
                    unlockCountInput.requestFocus()
                    return@setOnClickListener
                }
                
                // Both PIN and count are valid
                val previousCount = progressManager.getUnlockedPokemonCount()
                progressManager.unlockPokemon(count)
                val newCount = progressManager.getUnlockedPokemonCount()
                
                Log.d(TAG, "Unlocked $count Pokemon via admin modal. Previous: $previousCount, New total: $newCount")
                
                // Refresh the UI
                updateRecentlyUnlocked()
                loadPokemonData()
                
                // Show success message
                Toast.makeText(this, "Unlocked $count Pokemon! Total: $newCount", Toast.LENGTH_LONG).show()
                
                // Close the dialog
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Update grid layout for current orientation when resuming
        updateGridLayoutForOrientation()
    }

    private fun updateGridLayoutForOrientation() {
        val spanCount = calculateOptimalSpanCount()
        (recyclerView.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        pokemonAdapter.notifyDataSetChanged() // Refresh layout
    }

    companion object {
        private const val TAG = "PokemonActivity"
    }
}

// Data classes for Pokemon
data class Pokemon(
    val id: Int,
    val name: String,
    val imagePath: String,
    val isShiny: Boolean,
    val number: Int
)

data class PokemonFile(
    val prefix: Int,
    val pokenum: Int,
    val shiny: Boolean,
    val filename: String
)

// RecyclerView Adapter for Pokemon
class PokemonAdapter(
    private val context: Context,
    private var pokemon: List<Pokemon>,
    private val onPokemonClick: (Pokemon) -> Unit
) : RecyclerView.Adapter<PokemonAdapter.PokemonViewHolder>() {

    fun updatePokemon(newPokemon: List<Pokemon>) {
        pokemon = newPokemon
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PokemonViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.item_pokemon, parent, false)
        return PokemonViewHolder(view)
    }

    override fun onBindViewHolder(holder: PokemonViewHolder, position: Int) {
        holder.bind(pokemon[position])
    }

    override fun getItemCount() = pokemon.size

    inner class PokemonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val pokemonImage: ImageView = itemView.findViewById(R.id.pokemonImage)
        private val pokemonName: TextView = itemView.findViewById(R.id.pokemonName)

        fun bind(pokemon: Pokemon) {
            pokemonName.text = pokemon.name

            // Load image from assets
            try {
                val inputStream = context.assets.open(pokemon.imagePath)
                val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                pokemonImage.setImageDrawable(drawable)
                inputStream.close()
            } catch (e: Exception) {
                android.util.Log.e("PokemonAdapter", "Error loading Pokemon image: ${pokemon.imagePath}", e)
                pokemonImage.setImageResource(R.drawable.ic_launcher_foreground) // Fallback
            }

            itemView.setOnClickListener { onPokemonClick(pokemon) }
        }
    }
}

// Extension function to convert dp to pixels
private fun Int.dpToPx(): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        Resources.getSystem().displayMetrics
    ).toInt()
}
