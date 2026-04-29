package com.bwsc.watermeter

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.text.Html
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {

    private lateinit var requestQueue: RequestQueue
    private val sheetUrl = "https://script.google.com/macros/s/AKfycbzYWpalBcmJPqkyL1_8m5GMbgXad8SKwuKDEWZbu7mvmTHnx_PHv6bej8OnpEJq5QBG/exec"
    private val googleSheetUrl = "https://docs.google.com/spreadsheets/d/1S9RLnZjK5POmS9x8l9YSrCZnHtYl5grA7lajNB7HCB0/edit?gid=0#gid=0"
    private var existingCustomerIds: MutableList<String> = mutableListOf()
    // Street list is now dynamic and persisted in SharedPreferences
    private var streetList: MutableList<String> = mutableListOf()
    private val monthOptions = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private var lastResult: String? = null
    private lateinit var progressDialog: ProgressDialog
    private lateinit var sharedPreferences: SharedPreferences
    private val pendingSubmissions = mutableListOf<JSONObject>()
    private var selectedStreet: String? = null
    private var selectedMonth: String? = null
    private var currentCustomerName: String? = null
    private var previousMonthReading: Double? = null
    private var isCustomerValid: Boolean = false
    private var lastCustomerId: String? = null
    private var notepadContent: String = ""
    private var customerNamesList: List<String> = emptyList()
    private val dailyHistory = mutableListOf<Pair<String, String>>()
    // Store full history across days. Each entry is a JSON string with fields: id, name, timestamp
    private val fullHistory = mutableListOf<String>()
    private var historyDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())

    private lateinit var spinnerMonth: Spinner
    private lateinit var etCustomerId: EditText
    private lateinit var btnVerifyCustomer: Button
    private lateinit var btnSkipCustomer: Button
    private lateinit var tvCustomerName: TextView
    private lateinit var tvPreviousMonthReading: TextView
    private lateinit var etPreviousReading: EditText
    private lateinit var etCurrentReading: EditText
    private lateinit var etRemarks: EditText
    private lateinit var spinnerStreet: Spinner
    private lateinit var btnAddStreet: Button
    private lateinit var btnSubmit: Button
    private lateinit var btnBackup: Button
    private lateinit var btnPrint: Button
    private lateinit var tvLastCustomerId: TextView
    private lateinit var btnNotepad: Button
    private lateinit var btnSearch: Button
    private lateinit var btnList: Button
    private lateinit var btnHistory: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerMonth = findViewById(R.id.spinnerMonth)
        etCustomerId = findViewById(R.id.etCustomerId)
        btnVerifyCustomer = findViewById(R.id.btnVerifyCustomer)
        btnSkipCustomer = findViewById(R.id.btnSkipCustomer)
        tvCustomerName = findViewById(R.id.tvCustomerName)
        tvPreviousMonthReading = findViewById(R.id.tvPreviousMonthReading)
        etPreviousReading = findViewById(R.id.etPreviousReading)
        etCurrentReading = findViewById(R.id.etCurrentReading)
        etRemarks = findViewById(R.id.etRemarks)
        spinnerStreet = findViewById(R.id.spinnerStreet)
        btnAddStreet = findViewById(R.id.btnAddStreet)
        btnSubmit = findViewById(R.id.btnSubmit)
        btnBackup = findViewById(R.id.btnBackup)
        btnPrint = findViewById(R.id.btnPrint)
        tvLastCustomerId = findViewById(R.id.tvLastCustomerId)
        btnNotepad = findViewById(R.id.btnNotepad)
        btnSearch = findViewById(R.id.btnSearch)
        btnList = findViewById(R.id.btnList)
        btnHistory = findViewById(R.id.btnHistory)

        sharedPreferences = getSharedPreferences("WaterMeterPrefs", MODE_PRIVATE)

        requestQueue = Volley.newRequestQueue(this).apply { cache.clear() }

        val monthAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, monthOptions)
        monthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMonth.adapter = monthAdapter

        // Load persisted streets (or use defaults) and set adapter
        loadStreets()
        val streetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, streetList)
        streetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStreet.adapter = streetAdapter
        // Wire add street button
        btnAddStreet.setOnClickListener { showAddStreetDialog(streetAdapter) }
        // Delete street button
        val btnDeleteStreet = findViewById<Button>(R.id.btnDeleteStreet)
        btnDeleteStreet.setOnClickListener {
            val current = spinnerStreet.selectedItem as? String
            if (current.isNullOrEmpty()) {
                Toast.makeText(this, "No street selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(getString(R.string.delete_street))
                .setMessage(String.format(getString(R.string.delete_street_confirm), current))
                .setPositiveButton(getString(R.string.delete_street)) { _, _ ->
                    // Remove from list and persist
                    val idx = streetList.indexOfFirst { it.equals(current, ignoreCase = true) }
                    if (idx >= 0) {
                        streetList.removeAt(idx)
                        saveStreets()
                        streetAdapter.notifyDataSetChanged()
                        Toast.makeText(this, getString(R.string.street_deleted), Toast.LENGTH_SHORT).show()
                        // Clear cached customer IDs associated with this street
                        existingCustomerIds = existingCustomerIds.filterNot { it.endsWith("|$current", ignoreCase = true) }.toMutableList()
                        cacheCustomerIds()
                        // Reset selection
                        if (streetList.isNotEmpty()) {
                            spinnerStreet.setSelection(0)
                        } else {
                            tvCustomerName.visibility = View.GONE
                            tvPreviousMonthReading.visibility = View.GONE
                            btnSubmit.isEnabled = false
                            btnBackup.isEnabled = false
                            btnVerifyCustomer.isEnabled = false
                            btnSkipCustomer.isEnabled = false
                        }
                    } else {
                        Toast.makeText(this, "Street not found", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.delete_cancel), null)
                .show()
        }

        // Fetch available sheets from server (if available) and merge into streetList
        if (isNetworkAvailable()) {
            fetchAvailableStreets { serverList ->
                if (!serverList.isNullOrEmpty()) {
                    // Merge server list first, then keep any local-only streets
                    val merged = mutableListOf<String>()
                    serverList.forEach { name -> if (!merged.any { it.equals(name, ignoreCase = true) }) merged.add(name) }
                    streetList.forEach { local -> if (!merged.any { it.equals(local, ignoreCase = true) }) merged.add(local) }
                    streetList.clear()
                    streetList.addAll(merged)
                    streetAdapter.notifyDataSetChanged()
                    saveStreets()
                    // select previously saved selection if exists
                    spinnerStreet.setSelection(0)
                }
            }
        }

        btnSubmit.isEnabled = false
        btnBackup.isEnabled = false
        btnPrint.isEnabled = false
        btnVerifyCustomer.isEnabled = false
        btnSkipCustomer.isEnabled = false
        etCustomerId.isEnabled = false
        etPreviousReading.isEnabled = false
        etCurrentReading.isEnabled = false
        etRemarks.isEnabled = false
        spinnerMonth.isEnabled = true
        spinnerStreet.isEnabled = true

        loadCachedCustomerIds()
        loadLastCustomerId()
        loadPendingSubmissions()
        tvLastCustomerId.text = "Last Read Customer ID: ${lastCustomerId ?: "None"}"

        notepadContent = sharedPreferences.getString("notepadContent", "") ?: ""
        loadDailyHistory()
        loadFullHistory()

        spinnerMonth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedMonth = monthOptions[position]
                if (selectedMonth != null && selectedStreet != null) {
                    hideColumnsForStreetAndMonth(selectedStreet!!, selectedMonth!!)
                    if (isCustomerValid) {
                        fetchPreviousMonthReading(etCustomerId.text.toString().trim(), selectedStreet!!, selectedMonth!!) { reading ->
                            previousMonthReading = reading
                            if (reading != null) {
                                tvPreviousMonthReading.text = "Previous Month Reading: $reading m³"
                                tvPreviousMonthReading.visibility = View.VISIBLE
                            } else {
                                tvPreviousMonthReading.text = "Previous Month Reading: Not Available"
                                tvPreviousMonthReading.visibility = View.VISIBLE
                            }
                        }
                    } else {
                        tvPreviousMonthReading.visibility = View.GONE
                    }
                } else {
                    tvPreviousMonthReading.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                selectedMonth = null
                tvPreviousMonthReading.visibility = View.GONE
            }
        }

        btnVerifyCustomer.setOnClickListener {
            val customerId = etCustomerId.text.toString().trim()
            if (customerId.isEmpty()) {
                Toast.makeText(this, "Please enter a Customer ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedStreet == null || selectedMonth == null) {
                Toast.makeText(this, "Please select a street and month first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isNetworkAvailable()) {
                progressDialog = ProgressDialog(this).apply {
                    setMessage("Verifying customer ID: $customerId...")
                    setCancelable(false)
                    setCustomStyle()
                    show()
                }
                fetchCustomerData(customerId, selectedStreet!!, selectedMonth!!) {
                    progressDialog.dismiss()
                }
            } else {
                if (existingCustomerIds.any { it.equals("$customerId|$selectedStreet", ignoreCase = true) }) {
                    tvCustomerName.text = "Customer Name: (Offline - ID Verified)"
                    tvCustomerName.visibility = View.VISIBLE
                    tvPreviousMonthReading.text = "Previous Month Reading: (Offline - Not Available)"
                    tvPreviousMonthReading.visibility = View.VISIBLE
                    isCustomerValid = true
                } else {
                    tvCustomerName.text = "Customer Name: Not Found"
                    tvCustomerName.visibility = View.VISIBLE
                    tvPreviousMonthReading.visibility = View.GONE
                    isCustomerValid = false
                    Toast.makeText(this, "Please use a valid customer ID from the spreadsheet.", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnSkipCustomer.setOnClickListener {
            if (selectedStreet == null || selectedMonth == null) {
                Toast.makeText(this, "Please select a street and month first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentId = etCustomerId.text.toString().trim()
            val nextId = if (currentId.isEmpty()) {
                existingCustomerIds.firstOrNull { it.endsWith("|$selectedStreet") }?.removeSuffix("|$selectedStreet")
            } else {
                getNextCustomerId(currentId, selectedStreet!!)
            }

            if (nextId == null) {
                Toast.makeText(this, "No more customer IDs available for $selectedStreet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            etCustomerId.setText(nextId)
            if (isNetworkAvailable()) {
                progressDialog = ProgressDialog(this).apply {
                    setMessage("Verifying next customer ID: $nextId...")
                    setCancelable(false)
                    setCustomStyle()
                    show()
                }
                fetchCustomerData(nextId, selectedStreet!!, selectedMonth!!) {
                    progressDialog.dismiss()
                }
            } else {
                if (existingCustomerIds.any { it.equals("$nextId|$selectedStreet", ignoreCase = true) }) {
                    tvCustomerName.text = "Customer Name: (Offline - ID Verified)"
                    tvCustomerName.visibility = View.VISIBLE
                    tvPreviousMonthReading.text = "Previous Month Reading: (Offline - Not Available)"
                    tvPreviousMonthReading.visibility = View.VISIBLE
                    isCustomerValid = true
                } else {
                    tvCustomerName.text = "Customer Name: Not Found"
                    tvCustomerName.visibility = View.VISIBLE
                    tvPreviousMonthReading.visibility = View.GONE
                    isCustomerValid = false
                    Toast.makeText(this, "Please use a valid customer ID from the spreadsheet.", Toast.LENGTH_LONG).show()
                }
            }
        }

        spinnerStreet.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedStreet = if (position in 0 until streetList.size) streetList[position] else null
                if (isNetworkAvailable()) {
                    fetchCustomerIdsForStreet(selectedStreet!!) { success ->
                        if (success) {
                            btnSubmit.isEnabled = true
                            btnBackup.isEnabled = true
                            btnVerifyCustomer.isEnabled = true
                            btnSkipCustomer.isEnabled = true
                            etCustomerId.isEnabled = true
                            etPreviousReading.isEnabled = true
                            etCurrentReading.isEnabled = true
                            etRemarks.isEnabled = true
                            setSubmitListener(btnSubmit, btnPrint, etCustomerId, etPreviousReading, etCurrentReading, etRemarks, spinnerStreet, spinnerMonth)
                            if (selectedMonth != null) {
                                hideColumnsForStreetAndMonth(selectedStreet!!, selectedMonth!!)
                            }
                        } else {
                            if (existingCustomerIds.isNotEmpty()) {
                                Toast.makeText(this@MainActivity, "Using cached data due to weak connection", Toast.LENGTH_LONG).show()
                                btnSubmit.isEnabled = true
                                btnBackup.isEnabled = true
                                btnVerifyCustomer.isEnabled = true
                                btnSkipCustomer.isEnabled = true
                                etCustomerId.isEnabled = true
                                etPreviousReading.isEnabled = true
                                etCurrentReading.isEnabled = true
                                etRemarks.isEnabled = true
                                setSubmitListener(btnSubmit, btnPrint, etCustomerId, etPreviousReading, etCurrentReading, etRemarks, spinnerStreet, spinnerMonth)
                                if (selectedMonth != null) {
                                    hideColumnsForStreetAndMonth(selectedStreet!!, selectedMonth!!)
                                }
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to fetch customer IDs. Please try again.", Toast.LENGTH_LONG).show()
                                btnSubmit.isEnabled = true
                                btnSubmit.text = "Retry"
                                btnSubmit.setOnClickListener {
                                    fetchCustomerIdsForStreet(selectedStreet!!) { retrySuccess ->
                                        if (retrySuccess) {
                                            btnSubmit.isEnabled = true
                                            btnSubmit.text = "Submit Reading"
                                            btnBackup.isEnabled = true
                                            btnVerifyCustomer.isEnabled = true
                                            btnSkipCustomer.isEnabled = true
                                            etCustomerId.isEnabled = true
                                            etPreviousReading.isEnabled = true
                                            etCurrentReading.isEnabled = true
                                            etRemarks.isEnabled = true
                                            setSubmitListener(btnSubmit, btnPrint, etCustomerId, etPreviousReading, etCurrentReading, etRemarks, spinnerStreet, spinnerMonth)
                                            if (selectedMonth != null) {
                                                hideColumnsForStreetAndMonth(selectedStreet!!, selectedMonth!!)
                                            }
                                        } else {
                                            Toast.makeText(this@MainActivity, "Failed to connect. Please check your internet connection.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (existingCustomerIds.isNotEmpty()) {
                        Toast.makeText(this@MainActivity, "No internet connection. Using cached data.", Toast.LENGTH_LONG).show()
                        btnSubmit.isEnabled = true
                        btnBackup.isEnabled = true
                        btnVerifyCustomer.isEnabled = true
                        btnSkipCustomer.isEnabled = true
                        etCustomerId.isEnabled = true
                        etPreviousReading.isEnabled = true
                        etCurrentReading.isEnabled = true
                        etRemarks.isEnabled = true
                        setSubmitListener(btnSubmit, btnPrint, etCustomerId, etPreviousReading, etCurrentReading, etRemarks, spinnerStreet, spinnerMonth)
                        if (selectedMonth != null) {
                            hideColumnsForStreetAndMonth(selectedStreet!!, selectedMonth!!)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "No internet connection and no cached data available.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                selectedStreet = null
                tvCustomerName.visibility = View.GONE
                tvPreviousMonthReading.visibility = View.GONE
                isCustomerValid = false
                btnSkipCustomer.isEnabled = false
            }
        }

        // If we have streets loaded, ensure the spinner has a valid initial selection so
        // downstream logic (which expects a selectedStreet) can run safely.
        if (streetList.isNotEmpty()) {
            spinnerStreet.setSelection(0)
        }

        if (isNetworkAvailable()) {
            syncPendingSubmissions()
        }

        btnBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(googleSheetUrl))
            try {
                startActivity(intent)
                Toast.makeText(this@MainActivity, "Opening Google Sheet in browser. Please download the file manually.", Toast.LENGTH_LONG).show()
                Log.d("WaterMeter", "Redirecting to Google Sheet URL: $googleSheetUrl")
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error opening browser: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("WaterMeter", "Error opening browser: ${e.message}")
            }
        }

        btnPrint.setOnClickListener {
            val customerId = etCustomerId.text.toString().trim()
            val street = spinnerStreet.selectedItem.toString()
            val month = spinnerMonth.selectedItem.toString()

            if (customerId.isEmpty() || street.isEmpty() || month.isEmpty()) {
                Toast.makeText(this@MainActivity, "Please enter Customer ID, select a street, and select a month to reprint", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!existingCustomerIds.any { it.equals("$customerId|$street", ignoreCase = true) }) {
                Toast.makeText(this@MainActivity, "Customer ID $customerId does not exist in $street", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressDialog = ProgressDialog(this).apply {
                setMessage("Fetching previous reading for $customerId...")
                setCancelable(false)
                setCustomStyle()
                show()
            }

            fetchPreviousReading(customerId, street, month) { sheetPresentReading, customerName, _, fetchedRemarks, previousReading, usage, bill ->
                progressDialog.dismiss()
                if (sheetPresentReading == null || customerName == null || previousReading == null || usage == null || bill == null) {
                    Toast.makeText(this@MainActivity, "Failed to fetch previous record for $customerId in $street for $month", Toast.LENGTH_LONG).show()
                    Log.e("WaterMeter", "Failed to fetch previous record for $customerId in $street for $month")
                    return@fetchPreviousReading
                }

                val afterDueFee = (bill * 0.15).roundToLong().toDouble()
                val afterDue = bill + afterDueFee

                val calendar = Calendar.getInstance()
                val monthIndex = monthOptions.indexOf(month)
                calendar.set(Calendar.MONTH, monthIndex + 1)
                calendar.set(Calendar.DAY_OF_MONTH, 25)
                val dueDate = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(calendar.time)

                val resultText = """
                    -----------------------------------
                    BOLO MULTIPURPOSE COOPERATIVE
                    Rizal Street, Bolo, Bauan, Batangas
                    Tel. No. 043-233-1084
                    Email: brgybolowater@gmail.com
                    -----------------------------------
                    Customer: $customerName
                    ID: $customerId
                    Street: $street
                    Month: $month
                    Due: $dueDate
                    After Due: PHP $afterDue
                    Prev: $previousReading m³
                    Pres: $sheetPresentReading m³
                    Usage: $usage m³
                    ---------
                    BILL: PHP $bill
                    ---------
                    Garbage: PHP 70-100
                    Remarks: $fetchedRemarks
                    -----------------------------------
                """.trimIndent()

                lastResult = resultText
                btnPrint.isEnabled = true
                printReceipt(resultText)
            }
        }

        btnNotepad.setOnClickListener {
            showNotepadDialog()
        }

        btnSearch.setOnClickListener {
            if (selectedStreet == null || selectedMonth == null) {
                Toast.makeText(this, "Please select a street and month first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressDialog = ProgressDialog(this).apply {
                setMessage("Fetching customer names for $selectedStreet...")
                setCancelable(false)
                setCustomStyle()
                show()
            }

            fetchCustomerNames(selectedStreet!!) { names ->
                progressDialog.dismiss()
                customerNamesList = names
                if (names.isEmpty()) {
                    Toast.makeText(this, "No customer names found for $selectedStreet", Toast.LENGTH_LONG).show()
                } else {
                    showSearchDialog()
                }
            }
        }

        btnList.setOnClickListener {
            if (selectedStreet == null || selectedMonth == null) {
                Toast.makeText(this, "Please select a street and month first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showCustomerList(selectedStreet!!, selectedMonth!!)
        }

        btnHistory.setOnClickListener {
            showHistoryDialog()
        }
    }

    private fun ProgressDialog.setCustomStyle() {
        try {
            val messageField = ProgressDialog::class.java.getDeclaredField("mMessageView")
            messageField.isAccessible = true
            val messageView = messageField.get(this) as? TextView
            messageView?.setTextColor(resources.getColor(R.color.textPrimary, theme))
            window?.setBackgroundDrawableResource(R.color.dialogBackground)
        } catch (e: Exception) {
            Log.w("WaterMeter", "Failed to style ProgressDialog: ${e.message}")
        }
    }

    private fun showNotepadDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_notepad, null)
        val etNotepad = dialogView.findViewById<EditText>(R.id.etNotepad)
        etNotepad.setText(notepadContent)

        val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Notepad")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                notepadContent = etNotepad.text.toString()
                saveNotepadContent()
                Toast.makeText(this, "Notes saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        dialog.show()

        // Apply custom colors to Save and Cancel buttons
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(resources.getColor(R.color.button_save_color, theme))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(resources.getColor(R.color.button_cancel_color, theme))
    }

    // Persist full history list (JSON entries) to SharedPreferences
    private fun saveFullHistory() {
        val editor = sharedPreferences.edit()
        try {
            val array = JSONArray()
            fullHistory.forEach { array.put(it) }
            editor.putString("fullHistory", array.toString())
            editor.apply()
        } catch (e: Exception) {
            Log.e("WaterMeter", "Failed to save fullHistory: ${e.message}")
        }
    }

    private fun loadFullHistory() {
        val raw = sharedPreferences.getString("fullHistory", null)
        if (!raw.isNullOrEmpty()) {
            try {
                val array = JSONArray(raw)
                fullHistory.clear()
                for (i in 0 until array.length()) {
                    fullHistory.add(array.getString(i))
                }
            } catch (e: Exception) {
                Log.e("WaterMeter", "Failed to load fullHistory: ${e.message}")
            }
        }
    }

    // Helper: add structured entry to fullHistory: {id,name,timestamp,receipt}
    private fun addToFullHistory(customerId: String, customerName: String, receipt: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())
        val obj = JSONObject().apply {
            put("id", customerId)
            put("name", customerName)
            put("timestamp", timestamp)
            put("receipt", receipt)
        }
        fullHistory.add(obj.toString())
        saveFullHistory()
    }

    // Add a simple daily history entry (id + name + timestamp) and persist
    private fun addToHistory(customerId: String, customerName: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())
            val display = "$customerName ($timestamp)"
            dailyHistory.add(Pair(customerId, display))
            saveDailyHistory()
        } catch (e: Exception) {
            Log.e("WaterMeter", "Failed to add to daily history: ${e.message}")
        }
    }

    private fun fetchCustomerNames(street: String, callback: (List<String>) -> Unit) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection. Cannot fetch customer names.", Toast.LENGTH_LONG).show()
            callback(emptyList())
            return
        }

        val url = "$sheetUrl?fetchAll=true&street=$street"
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                try {
                    val jsonArray = JSONArray(response)
                    val names = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        val row = jsonArray.getJSONArray(i)
                        val customerName = row.getString(1).trim()
                        if (customerName.isNotEmpty()) {
                            names.add(customerName)
                        }
                    }
                    callback(names)
                } catch (e: Exception) {
                    Log.e("WaterMeter", "Error fetching customer names: ${e.message}")
                    Toast.makeText(this, "Error fetching names: ${e.message}", Toast.LENGTH_LONG).show()
                    callback(emptyList())
                }
            },
            { error ->
                Log.e("WaterMeter", "Error fetching customer names: ${error.message}")
                Toast.makeText(this, "Error fetching names: ${error.message}", Toast.LENGTH_LONG).show()
                callback(emptyList())
            })

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun showSearchDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_search, null)
        val autoCompleteTextView = dialogView.findViewById<AutoCompleteTextView>(R.id.etSearchName)

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, customerNamesList)
        autoCompleteTextView.setAdapter(adapter)
        autoCompleteTextView.threshold = 1
        autoCompleteTextView.setDropDownBackgroundResource(R.color.dialogBackground)

        val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Search Customer by Name")
            .setView(dialogView)
            .setPositiveButton("Search") { _, _ ->
                val searchName = autoCompleteTextView.text.toString().trim()
                if (searchName.isEmpty()) {
                    Toast.makeText(this, "Please enter or select a name to search", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                searchCustomerByName(searchName, selectedStreet!!, selectedMonth!!)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()

        // Apply custom colors to Search (Save) and Cancel buttons
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(resources.getColor(R.color.button_save_color, theme))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(resources.getColor(R.color.button_cancel_color, theme))
    }

    private fun showCustomerList(street: String, month: String) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection. Cannot fetch customer list.", Toast.LENGTH_LONG).show()
            return
        }

        progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching customer list for $street ($month)...")
            setCancelable(false)
            setCustomStyle()
            show()
        }

        val url = "$sheetUrl?fetchAll=true&street=$street"
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                progressDialog.dismiss()
                try {
                    val jsonArray = JSONArray(response)
                    if (jsonArray.length() == 0) {
                        Toast.makeText(this, "No customers found for $street", Toast.LENGTH_LONG).show()
                        return@StringRequest
                    }

                    val customerEntries = mutableListOf<Pair<String, String>>()
                    val monthIndex = monthOptions.indexOf(month)
                    val colOffset = 3 + (monthIndex * 6)

                    for (i in 0 until jsonArray.length()) {
                        val row = jsonArray.getJSONArray(i)
                        val customerId = row.getString(0).trim()
                        val customerName = row.getString(1).trim()
                        val presentReading = if (row.length() > colOffset + 1) row.getString(colOffset + 1).toDoubleOrNull() else null
                        val usage = if (row.length() > colOffset + 2) row.getString(colOffset + 2).toDoubleOrNull() else null
                        val bill = if (row.length() > colOffset + 3) row.getString(colOffset + 3).toDoubleOrNull() else null
                        val hasReading = presentReading != null && usage != null && bill != null
                        val color = if (hasReading) "#00FF00" else "#FF0000"
                        val entry = "<font color='$color'>ID: $customerId - $customerName</font><br>"
                        customerEntries.add(Pair(customerId, entry))
                    }

                    customerEntries.sortWith { entry1, entry2 ->
                        val id1 = entry1.first
                        val id2 = entry2.first
                        val regex = Regex("(\\d+)([A-Za-z]?)")
                        val match1 = regex.matchEntire(id1)
                        val match2 = regex.matchEntire(id2)
                        if (match1 == null || match2 == null) id1.compareTo(id2)
                        else {
                            val num1 = match1.groups[1]?.value?.toIntOrNull() ?: 0
                            val suffix1 = match1.groups[2]?.value ?: ""
                            val num2 = match2.groups[1]?.value?.toIntOrNull() ?: 0
                            val suffix2 = match2.groups[2]?.value ?: ""
                            if (num1 != num2) num1.compareTo(num2) else suffix1.compareTo(suffix2)
                        }
                    }

                    val entriesPerPage = 15
                    var currentPage = 0
                    val totalPages = (customerEntries.size + entriesPerPage - 1) / entriesPerPage

                    val dialogView = layoutInflater.inflate(R.layout.dialog_customer_list, null)
                    val tvList = dialogView.findViewById<TextView>(R.id.tvCustomerList)

                    fun updatePage() {
                        val startIndex = currentPage * entriesPerPage
                        val endIndex = minOf(startIndex + entriesPerPage, customerEntries.size)
                        val pageEntries = customerEntries.subList(startIndex, endIndex)
                        val pageContent = StringBuilder()
                        pageEntries.forEach { pageContent.append(it.second) }
                        tvList.text = Html.fromHtml(pageContent.toString(), Html.FROM_HTML_MODE_LEGACY)
                    }

                    updatePage()

                    val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
                        .setTitle("Customer List - $street ($month) (Page ${currentPage + 1}/$totalPages)")
                        .setView(dialogView)
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .setNeutralButton("Next", null)
                        .setNegativeButton("Previous", null)
                        .create()

                    dialog.show()

                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(resources.getColor(R.color.textPrimary, theme))
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(resources.getColor(R.color.textPrimary, theme))
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(resources.getColor(R.color.textPrimary, theme))

                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        if (currentPage < totalPages - 1) {
                            currentPage++
                            updatePage()
                            dialog.setTitle("Customer List - $street ($month) (Page ${currentPage + 1}/$totalPages)")
                        } else {
                            Toast.makeText(this, "This is the last page", Toast.LENGTH_SHORT).show()
                        }
                    }

                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                        if (currentPage > 0) {
                            currentPage--
                            updatePage()
                            dialog.setTitle("Customer List - $street ($month) (Page ${currentPage + 1}/$totalPages)")
                        } else {
                            Toast.makeText(this, "This is the first page", Toast.LENGTH_SHORT).show()
                        }
                    }

                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                } catch (e: Exception) {
                    Log.e("WaterMeter", "Error processing customer list: ${e.message}")
                    Toast.makeText(this, "Error processing list: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            { error ->
                progressDialog.dismiss()
                Log.e("WaterMeter", "Error fetching customer list: ${error.message}")
                Toast.makeText(this, "Error fetching list: ${error.message}", Toast.LENGTH_LONG).show()
            })

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun searchCustomerByName(searchName: String, street: String, month: String) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection. Cannot search.", Toast.LENGTH_LONG).show()
            return
        }

        progressDialog = ProgressDialog(this).apply {
            setMessage("Searching for '$searchName' in $street for $month...")
            setCancelable(false)
            setCustomStyle()
            show()
        }

        val url = "$sheetUrl?fetchAll=true&street=$street"
        Log.d("WaterMeter", "Fetching data from URL: $url")
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                progressDialog.dismiss()
                Log.d("WaterMeter", "Raw response: $response")
                try {
                    val jsonArray = JSONArray(response)
                    Log.d("WaterMeter", "Number of records fetched: ${jsonArray.length()}")
                    var found = false
                    for (i in 0 until jsonArray.length()) {
                        val row = jsonArray.getJSONArray(i)
                        val customerName = row.getString(1).trim()
                        val customerId = row.getString(0).trim()
                        if (customerName.equals(searchName, ignoreCase = true)) {
                            Log.d("WaterMeter", "Match found for '$customerName'")
                            progressDialog = ProgressDialog(this).apply {
                                setMessage("Fetching previous reading for $customerId...")
                                setCancelable(false)
                                setCustomStyle()
                                show()
                            }
                            fetchPreviousReading(customerId, street, month) { sheetPresentReading, fetchedCustomerName, _, fetchedRemarks, previousReading, usage, bill ->
                                progressDialog.dismiss()
                                val presentReadingToUse = sheetPresentReading ?: 0.0
                                val customerNameToUse = fetchedCustomerName ?: customerName
                                val remarksToUse = fetchedRemarks ?: ""
                                val previousReadingToUse = previousReading ?: 0.0
                                val usageToUse = usage ?: (presentReadingToUse - previousReadingToUse)
                                val billToUse = bill ?: calculateBill(usageToUse)

                                if (customerNameToUse == null) {
                                    Toast.makeText(this@MainActivity, "Customer name not retrieved for $customerId", Toast.LENGTH_LONG).show()
                                    return@fetchPreviousReading
                                }

                                val afterDueFee = (billToUse * 0.15).roundToLong().toDouble()
                                val afterDue = billToUse + afterDueFee

                                val calendar = Calendar.getInstance()
                                val monthIndex = monthOptions.indexOf(month)
                                calendar.set(Calendar.MONTH, monthIndex + 1)
                                calendar.set(Calendar.DAY_OF_MONTH, 25)
                                val dueDate = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(calendar.time)

                                val resultText = """
                                    -----------------------------------
                                    BOLO MULTIPURPOSE COOPERATIVE
                                    Rizal Street, Bolo, Bauan, Batangas
                                    Tel. No. 043-233-1084
                                    Email: brgybolowater@gmail.com
                                    -----------------------------------
                                    Customer: $customerNameToUse
                                    ID: $customerId
                                    Street: $street
                                    Month: $month
                                    Due: $dueDate
                                    After Due: PHP $afterDue
                                    Prev: $previousReadingToUse m³
                                    Pres: $presentReadingToUse m³
                                    Usage: $usageToUse m³
                                    ---------
                                    BILL: PHP $billToUse
                                    ---------
                                    Garbage: PHP 70-100
                                    Remarks: $remarksToUse
                                    -----------------------------------
                                """.trimIndent()

                                lastResult = resultText
                                btnPrint.isEnabled = true
                                printReceipt(resultText)
                                Toast.makeText(this@MainActivity, "Found $customerNameToUse. Receipt printed.", Toast.LENGTH_LONG).show()
                            }
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        Toast.makeText(this@MainActivity, "No customer found with name '$searchName' in $street", Toast.LENGTH_LONG).show()
                        Log.w("WaterMeter", "No match found for '$searchName' in $street")
                    }
                } catch (e: Exception) {
                    Log.e("WaterMeter", "Error parsing search response: ${e.message}")
                    Toast.makeText(this@MainActivity, "Error searching: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            { error ->
                progressDialog.dismiss()
                Log.e("WaterMeter", "Error searching customer: ${error.message}")
                Toast.makeText(this@MainActivity, "Error searching: ${error.message}", Toast.LENGTH_LONG).show()
            })

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun setSubmitListener(
        btnSubmit: Button,
        btnPrint: Button,
        etCustomerId: EditText,
        etPreviousReading: EditText,
        etCurrentReading: EditText,
        etRemarks: EditText,
        spinnerStreet: Spinner,
        spinnerMonth: Spinner
    ) {
        btnSubmit.setOnClickListener {
            if (!isCustomerValid) {
                Toast.makeText(this@MainActivity, "Cannot submit: Please verify the customer ID first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val customerId = etCustomerId.text.toString().trim()
            val previousReadingInput = etPreviousReading.text.toString().toDoubleOrNull()
            val presentReading = etCurrentReading.text.toString().toDoubleOrNull()
            val street = spinnerStreet.selectedItem.toString()
            val month = spinnerMonth.selectedItem.toString()
            val remarks = etRemarks.text.toString()

            if (customerId.isEmpty() || presentReading == null || month.isEmpty()) {
                Toast.makeText(this@MainActivity, "Please enter a valid Customer ID, present reading, and select a month", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Robust check: server may return IDs in different formats. Accept either exact "ID|Street",
            // or any cached entry whose ID part (before '|') matches customerId.
            val existsLocally = existingCustomerIds.any {
                val entry = it.trim()
                if (entry.equals("$customerId|$street", ignoreCase = true)) return@any true
                val idPart = entry.split("|").getOrNull(0)?.trim()
                idPart?.equals(customerId, ignoreCase = true) == true
            }

            // If not found locally, but we have network, try an online verification before rejecting.
            if (!existsLocally) {
                if (isNetworkAvailable()) {
                    // Show a quick progress and verify with the server
                    progressDialog = ProgressDialog(this).apply {
                        setMessage("Verifying customer ID on server: $customerId...")
                        setCancelable(false)
                        setCustomStyle()
                        show()
                    }
                    // fetchCustomerData will set isCustomerValid and update UI if found
                    fetchCustomerData(customerId, street, month) {
                        progressDialog.dismiss()
                        if (!isCustomerValid) {
                            Toast.makeText(this@MainActivity, "Customer ID $customerId does not exist in $street", Toast.LENGTH_SHORT).show()
                            return@fetchCustomerData
                        }
                        // proceed with submission now that server verified the customer
                        val previousReadingToUse = previousReadingInput ?: previousMonthReading ?: 0.0
                        if (previousReadingInput == null && previousMonthReading == null) {
                            Toast.makeText(this@MainActivity, "No previous reading found. Using 0.0 as the previous reading.", Toast.LENGTH_LONG).show()
                        }
                        processReading(customerId, currentCustomerName ?: "", street, month, previousReadingToUse, presentReading, remarks, btnPrint)
                    }
                    return@setOnClickListener
                } else {
                    Toast.makeText(this@MainActivity, "Customer ID $customerId does not exist in $street", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            if (currentCustomerName == null) {
                Toast.makeText(this@MainActivity, "Customer not found. Please verify the Customer ID.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val previousReadingToUse = previousReadingInput ?: previousMonthReading ?: 0.0
            if (previousReadingInput == null && previousMonthReading == null) {
                Toast.makeText(this@MainActivity, "No previous reading found. Using 0.0 as the previous reading.", Toast.LENGTH_LONG).show()
            }

            processReading(customerId, currentCustomerName!!, street, month, previousReadingToUse, presentReading, remarks, btnPrint)
        }
    }

    private fun fetchCustomerIdsForStreet(street: String, callback: (Boolean) -> Unit) {
        progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching customer IDs for $street...")
            setCancelable(false)
            setCustomStyle()
            show()
        }

        val url = "$sheetUrl?fetchIds=true&street=$street"
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                progressDialog.dismiss()
                existingCustomerIds.clear()
                existingCustomerIds.addAll(response.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                Log.d("WaterMeter", "Fetched Customer IDs for $street: $existingCustomerIds")
                cacheCustomerIds()
                callback(true)
            },
            { error ->
                progressDialog.dismiss()
                Log.e("WaterMeter", "Error fetching customer IDs for $street: ${error.message}")
                callback(false)
            })

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun fetchCustomerName(customerId: String, street: String, callback: (String?) -> Unit) {
        if (!isNetworkAvailable()) {
            callback(null)
            return
        }

        val url = "$sheetUrl?customerId=$customerId&street=$street"
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.has("error")) {
                        callback(null)
                        return@StringRequest
                    }
                    val customerName = jsonResponse.optString("customerName")
                    callback(customerName)
                } catch (e: Exception) {
                    Log.e("WaterMeter", "Error parsing customer name response: ${e.message}")
                    callback(null)
                }
            },
            { error ->
                Log.e("WaterMeter", "Error fetching customer name: ${error.message}")
                callback(null)
            })

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun fetchPreviousMonthReading(
        customerId: String,
        street: String,
        month: String,
        callback: (Double?) -> Unit
    ) {
        if (!isNetworkAvailable()) {
            callback(null)
            return
        }

        val previousMonth = getPreviousMonth(month)
        val url = "$sheetUrl?customerId=$customerId&street=$street&month=$previousMonth"
        Log.d("WaterMeter", "Fetching previous month reading for URL: $url")
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                Log.d("WaterMeter", "Raw fetchPreviousMonthReading response: '$response'")
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.has("error")) {
                        callback(null)
                        return@StringRequest
                    }
                    val presentReading = jsonResponse.optString("presentReading").toDoubleOrNull()
                    callback(presentReading)
                } catch (e: Exception) {
                    Log.e("WaterMeter", "Error parsing previous month reading: ${e.message}")
                    callback(null)
                }
            },
            { error ->
                Log.e("WaterMeter", "Error fetching previous month reading: ${error.message}")
                callback(null)
            })

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun fetchPreviousReading(
        customerId: String,
        street: String,
        month: String,
        callback: (Double?, String?, String?, String?, Double?, Double?, Double?) -> Unit
    ) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection. Cannot fetch previous reading.", Toast.LENGTH_LONG).show()
            callback(null, null, null, null, null, null, null)
            return
        }

        val url = "$sheetUrl?customerId=$customerId&street=$street&month=$month"
        Log.d("WaterMeter", "Fetching previous reading for URL: $url")
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                Log.d("WaterMeter", "Raw fetchPreviousReading response: '$response'")
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.has("error")) {
                        Log.w("WaterMeter", "Error in response: ${jsonResponse.getString("error")}")
                        val customerName = jsonResponse.optString("customerName", null)
                        callback(null, customerName, null, "", null, null, null)
                        return@StringRequest
                    }
                    val presentReading = jsonResponse.optString("presentReading").toDoubleOrNull()
                    val customerName = jsonResponse.optString("customerName")
                    val remarks = jsonResponse.optString("remarks", "")
                    val previousReading = jsonResponse.optString("previousReading").toDoubleOrNull()
                    val usage = jsonResponse.optString("usage").toDoubleOrNull()
                    val bill = jsonResponse.optString("bill").toDoubleOrNull()
                    Log.d("WaterMeter", "Parsed data: present=$presentReading, name=$customerName, remarks=$remarks, prev=$previousReading, usage=$usage, bill=$bill")
                    callback(presentReading, customerName, null, remarks, previousReading, usage, bill)
                } catch (e: Exception) {
                    Log.e("WaterMeter", "Error parsing response: ${e.message}")
                    val jsonResponse = JSONObject(response)
                    val customerName = jsonResponse.optString("customerName", null)
                    callback(null, customerName, null, "", null, null, null)
                }
            },
            { error ->
                Log.e("WaterMeter", "Error fetching previous reading: ${error.message}")
                Toast.makeText(this@MainActivity, "Error fetching previous reading: ${error.message}", Toast.LENGTH_LONG).show()
                callback(null, null, null, null, null, null, null)
            })

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun fetchCustomerData(customerId: String, street: String, month: String, onComplete: (() -> Unit)? = null) {
        val url = "$sheetUrl?customerId=$customerId&street=$street&month=$month"
        Log.d("WaterMeter", "Fetching customer data from: $url")

        val request = StringRequest(
            Request.Method.GET,
            url,
            { response ->
                Log.d("WaterMeter", "Fetch Response: $response")
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.has("error")) {
                        val errorMessage = jsonResponse.getString("error")
                        Toast.makeText(this, "$errorMessage: $customerId in $street", Toast.LENGTH_LONG).show()
                        currentCustomerName = null
                        previousMonthReading = null
                        tvCustomerName.text = "Customer Name: Not Found"
                        tvCustomerName.visibility = View.VISIBLE
                        tvPreviousMonthReading.text = "Previous Month Reading: Not Available"
                        tvPreviousMonthReading.visibility = View.VISIBLE
                        isCustomerValid = false
                        Toast.makeText(this, "Please use a valid customer ID from the spreadsheet.", Toast.LENGTH_LONG).show()
                    } else {
                        val returnedCustomerId = jsonResponse.optString("customerId", "")
                        if (returnedCustomerId.isNotEmpty() && !returnedCustomerId.equals(customerId, ignoreCase = true)) {
                            Log.e("WaterMeter", "Customer ID mismatch: Requested $customerId, but received data for $returnedCustomerId")
                            Toast.makeText(this, "Error: Customer ID mismatch for $customerId in $street. Please try again.", Toast.LENGTH_LONG).show()
                            currentCustomerName = null
                            previousMonthReading = null
                            tvCustomerName.text = "Customer Name: Not Found"
                            tvCustomerName.visibility = View.VISIBLE
                            tvPreviousMonthReading.text = "Previous Month Reading: Not Available"
                            tvPreviousMonthReading.visibility = View.VISIBLE
                            isCustomerValid = false
                            return@StringRequest
                        }

                        currentCustomerName = jsonResponse.getString("customerName")
                        val presentReading = jsonResponse.getString("presentReading")
                        previousMonthReading = if (presentReading.isNotEmpty()) presentReading.toDoubleOrNull() else null
                        val remarks = jsonResponse.getString("remarks")
                        tvCustomerName.text = "Customer Name: $currentCustomerName"
                        tvCustomerName.visibility = View.VISIBLE
                        tvPreviousMonthReading.text = if (previousMonthReading != null) "Previous Month Reading: $previousMonthReading m³" else "Previous Month Reading: Not Available"
                        tvPreviousMonthReading.visibility = View.VISIBLE
                        etRemarks.setText(remarks)
                        isCustomerValid = true
                    }
                } catch (e: Exception) {
                    Log.e("WaterMeter", "Error parsing fetch response: ${e.message}", e)
                    Toast.makeText(this, "Error fetching customer data: ${e.message}", Toast.LENGTH_LONG).show()
                    isCustomerValid = false
                }
                onComplete?.invoke()
            },
            { error ->
                Log.e("WaterMeter", "Fetch Error: ${error.message}")
                Toast.makeText(this, "Failed to fetch customer data: ${error.message ?: "Unknown error"}. Check your internet connection.", Toast.LENGTH_LONG).show()
                isCustomerValid = false
                onComplete?.invoke()
            }
        )

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun processReading(
        customerId: String,
        customerName: String,
        street: String,
        month: String,
        previousReading: Double,
        presentReading: Double,
        remarks: String,
        btnPrint: Button
    ) {
        if (presentReading < previousReading) {
            Toast.makeText(this@MainActivity, "Present reading cannot be less than previous reading ($previousReading m³)", Toast.LENGTH_SHORT).show()
            Log.w("WaterMeter", "Validation failed: presentReading ($presentReading) < previousReading ($previousReading)")
            return
        }

        val usage = presentReading - previousReading
        val bill = calculateBill(usage)
        val afterDueFee = (bill * 0.15).roundToLong().toDouble()
        val afterDue = bill + afterDueFee

        val calendar = Calendar.getInstance()
        val monthIndex = monthOptions.indexOf(month)
        calendar.set(Calendar.MONTH, monthIndex + 1)
        calendar.set(Calendar.DAY_OF_MONTH, 25)
        val dueDate = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(calendar.time)

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())

        val resultText = """
            -----------------------------------
            BOLO MULTIPURPOSE COOPERATIVE
            Rizal Street, Bolo, Bauan, Batangas
            Tel. No. 043-233-1084
            Email: brgybolowater@gmail.com
            -----------------------------------
            Customer: $customerName
            ID: $customerId
            Street: $street
            Month: $month
            Due: $dueDate
            After Due: PHP $afterDue
            Prev: $previousReading m³
            Pres: $presentReading m³
            Usage: $usage m³
            ---------
            BILL: PHP $bill
            ---------
            Garbage: PHP 70-100
            Remarks: $remarks
            -----------------------------------
        """.trimIndent()

        lastCustomerId = customerId
        tvLastCustomerId.text = "Last Read Customer ID: $lastCustomerId"
        saveLastCustomerId()
        addToHistory(customerId, customerName)
        // Save the full printed receipt so it can be reprinted offline later
        addToFullHistory(customerId, customerName, resultText)

        Log.d("WaterMeter", "Result Text: $resultText")
        lastResult = resultText
        btnPrint.isEnabled = true
        printReceipt(resultText)
        sendToGoogleSheets(customerId, customerName, street, month, previousReading, presentReading, usage, bill, timestamp, remarks)
    }

    private fun calculateBill(usage: Double): Double {
        val rawBill = when {
            usage <= 5 -> 85.0
            usage <= 10 -> 85.0 + ((usage - 5) * 18.0)
            usage <= 20 -> 175.0 + ((usage - 10) * 19.5)
            else -> 370.0 + ((usage - 20) * 20.0)
        }
        return rawBill.roundToLong().toDouble()
    }

    private fun sendToGoogleSheets(
        customerId: String,
        customerName: String,
        street: String,
        month: String,
        previousReading: Double,
        presentReading: Double,
        usage: Double,
        bill: Double,
        timestamp: String,
        remarks: String
    ) {
        val normalizedMonth = month.trim().lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
        Log.d("WaterMeter", "Original month: '$month'")
        Log.d("WaterMeter", "Normalized month: '$normalizedMonth'")

        if (!monthOptions.contains(normalizedMonth)) {
            Log.e("WaterMeter", "Invalid month value: '$normalizedMonth'. Expected one of: ${monthOptions.joinToString()}")
            Toast.makeText(this, "Invalid month selected: $normalizedMonth. Please select a valid month.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d("WaterMeter", "Validated month being sent to Google Sheets: '$normalizedMonth'")

        val jsonObject = JSONObject().apply {
            put("customerId", customerId)
            put("customerName", customerName)
            put("previousReading", previousReading)
            put("presentReading", presentReading)
            put("usage", usage)
            put("bill", bill)
            put("timestamp", timestamp)
            put("street", street)
            put("month", normalizedMonth)
            put("remarks", remarks)
        }

        if (!isNetworkAvailable()) {
            pendingSubmissions.add(jsonObject)
            savePendingSubmissions()
            Toast.makeText(this, "No internet connection. Data will be synced when online.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d("WaterMeter", "Sending POST data to $sheetUrl: $jsonObject")

        val request = object : StringRequest(
            Method.POST,
            sheetUrl,
            { response ->
                Log.d("WaterMeter", "POST Success Response: '$response'")
                if (response.trim() == "Success") {
                    Toast.makeText(this@MainActivity, "Data updated in Google Sheets", Toast.LENGTH_SHORT).show()
                    syncPendingSubmissions()
                    etCustomerId.setText("")
                    tvCustomerName.text = ""
                    tvCustomerName.visibility = View.GONE
                    tvPreviousMonthReading.text = ""
                    tvPreviousMonthReading.visibility = View.GONE
                    etPreviousReading.setText("")
                    etCurrentReading.setText("")
                    etRemarks.setText("")
                    currentCustomerName = null
                    previousMonthReading = null
                    isCustomerValid = false

                    val nextId = getNextCustomerId(customerId, street)
                    if (nextId != null) {
                        etCustomerId.setText(nextId)
                        fetchCustomerData(nextId, street, month) {
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "No next ID available", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e("WaterMeter", "Unexpected response: '$response'")
                    Toast.makeText(this@MainActivity, "Failed to update data: $response", Toast.LENGTH_LONG).show()
                    pendingSubmissions.add(jsonObject)
                    savePendingSubmissions()
                }
            },
            { error ->
                Log.e("WaterMeter", "POST Error: ${error.message}")
                Log.e("WaterMeter", "Error Details: Status=${error.networkResponse?.statusCode}, Response=${error.networkResponse?.data?.toString(Charsets.UTF_8)}")
                pendingSubmissions.add(jsonObject)
                savePendingSubmissions()
                Toast.makeText(this@MainActivity, "Failed to send data. Will retry when online.", Toast.LENGTH_LONG).show()
            }
        ) {
            override fun getBody(): ByteArray {
                return jsonObject.toString().toByteArray(Charsets.UTF_8)
            }

            override fun getBodyContentType(): String {
                return "application/json; charset=utf-8"
            }
        }

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    private fun getNextCustomerId(currentId: String, street: String): String? {
        val streetIds = existingCustomerIds.filter { it.endsWith("|$street") }.map { it.removeSuffix("|$street") }
        if (streetIds.isEmpty()) return null

        val sortedIds = streetIds.sortedWith { id1, id2 ->
            val regex = Regex("(\\d+)([A-Za-z]?)")
            val match1 = regex.matchEntire(id1) ?: return@sortedWith id1.compareTo(id2)
            val match2 = regex.matchEntire(id2) ?: return@sortedWith id1.compareTo(id2)
            val num1 = match1.groups[1]?.value?.toInt() ?: 0
            val num2 = match2.groups[1]?.value?.toInt() ?: 0
            val suffix1 = match1.groups[2]?.value ?: ""
            val suffix2 = match2.groups[2]?.value ?: ""
            if (num1 != num2) num1.compareTo(num2) else suffix1.compareTo(suffix2)
        }

        val currentIndex = sortedIds.indexOf(currentId)
        if (currentIndex == -1 || currentIndex == sortedIds.size - 1) return null

        return sortedIds[currentIndex + 1]
    }

    private fun printReceipt(receiptText: String) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "${getString(R.string.app_name)}_Receipt"

        printManager.print(jobName, object : PrintDocumentAdapter() {
            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                try {
                    val outputStream = FileOutputStream(destination?.fileDescriptor)
                    val pdfDocument = PdfDocument()

                    val pageWidth = (58 * 2.834).toInt() // 58mm width in points
                    val textPaint = android.graphics.Paint().apply {
                        textSize = 10f
                        color = android.graphics.Color.BLACK
                    }
                    val boldPaint = android.graphics.Paint().apply {
                        textSize = 10f
                        color = android.graphics.Color.BLACK
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    }
                    val billPaint = android.graphics.Paint().apply {
                        textSize = 14f // Larger font size for bill
                        color = android.graphics.Color.BLACK
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    }

                    val fontMetrics = textPaint.fontMetrics
                    val lineHeight = fontMetrics.bottom - fontMetrics.top
                    val billLineHeight = billPaint.fontMetrics.bottom - billPaint.fontMetrics.top
                    val maxWidth = pageWidth - 10f

                    val condensedText = receiptText
                        .replace("-----------------------------------", "----------")
                        .replace("\n\n", "\n")
                    val lines = condensedText.split("\n")
                    val wrappedLines = mutableListOf<String>()

                    for (line in lines) {
                        if (line.isBlank()) {
                            wrappedLines.add("")
                            continue
                        }
                        if (textPaint.measureText(line) <= maxWidth) {
                            wrappedLines.add(line)
                        } else {
                            val words = line.split(" ")
                            var currentLine = ""
                            for (word in words) {
                                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                                if (textPaint.measureText(testLine) <= maxWidth) {
                                    currentLine = testLine
                                } else {
                                    if (currentLine.isNotEmpty()) wrappedLines.add(currentLine)
                                    currentLine = word
                                }
                            }
                            if (currentLine.isNotEmpty()) wrappedLines.add(currentLine)
                        }
                    }

                    var totalHeight = 150f // Base padding
                    wrappedLines.forEach { line ->
                        totalHeight += if (line.contains("BILL: PHP")) billLineHeight else lineHeight
                    }
                    val pageHeight = totalHeight.toInt()
                    Log.d("WaterMeter", "Printing: wrappedLines=${wrappedLines.size}, lineHeight=$lineHeight, billLineHeight=$billLineHeight, pageHeight=$pageHeight")

                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas

                    var yPos = 20f
                    for (line in wrappedLines) {
                        val paint = when {
                            line.contains("BILL: PHP") -> billPaint
                            line.contains("---------") -> boldPaint
                            else -> textPaint
                        }
                        canvas.drawText(line, 5f, yPos, paint)
                        yPos += if (line.contains("BILL: PHP")) billLineHeight else lineHeight
                    }

                    Log.d("WaterMeter", "Final yPos=$yPos, remaining space=${pageHeight - yPos}")

                    pdfDocument.finishPage(page)
                    pdfDocument.writeTo(outputStream)
                    pdfDocument.close()
                    outputStream.close()
                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    Log.e("WaterMeter", "Error printing: ${e.message}")
                    callback?.onWriteFailed(e.message)
                }
            }

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }

                val textPaint = android.graphics.Paint().apply {
                    textSize = 10f
                    color = android.graphics.Color.BLACK
                }
                val billPaint = android.graphics.Paint().apply {
                    textSize = 14f
                    color = android.graphics.Color.BLACK
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                }
                val fontMetrics = textPaint.fontMetrics
                val lineHeight = fontMetrics.bottom - fontMetrics.top
                val billLineHeight = billPaint.fontMetrics.bottom - billPaint.fontMetrics.top
                val maxWidth = (58 * 2.834 - 10).toFloat()

                val condensedText = receiptText
                    .replace("-----------------------------------", "----------")
                    .replace("\n\n", "\n")
                val lines = condensedText.split("\n")
                val wrappedLines = mutableListOf<String>()
                for (line in lines) {
                    if (line.isBlank()) {
                        wrappedLines.add("")
                        continue
                    }
                    val paint = if (line.contains("BILL: PHP")) billPaint else textPaint
                    if (paint.measureText(line) <= maxWidth) {
                        wrappedLines.add(line)
                    } else {
                        val words = line.split(" ")
                        var currentLine = ""
                        for (word in words) {
                            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                            if (paint.measureText(testLine) <= maxWidth) {
                                currentLine = testLine
                            } else {
                                if (currentLine.isNotEmpty()) wrappedLines.add(currentLine)
                                currentLine = word
                            }
                        }
                        if (currentLine.isNotEmpty()) wrappedLines.add(currentLine)
                    }
                }

                var totalHeight = 150f
                wrappedLines.forEach { line ->
                    totalHeight += if (line.contains("BILL: PHP")) billLineHeight else lineHeight
                }
                val pageHeightMils = (totalHeight * 39.37).toInt()
                Log.d("WaterMeter", "Layout: wrappedLines=${wrappedLines.size}, lineHeight=$lineHeight, billLineHeight=$billLineHeight, pageHeightMils=$pageHeightMils")

                val printAttributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize("58mm_Receipt", "58mm Receipt", 2283, pageHeightMils))
                    .setResolution(PrintAttributes.Resolution("203dpi", "203 dpi", 203, 203))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()

                callback?.onLayoutFinished(
                    android.print.PrintDocumentInfo.Builder("receipt.pdf")
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build(),
                    true
                )
            }
        }, null)
    }

    private fun showHistoryDialog() {
        // Ensure dailyHistory resets when date changes
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
        if (currentDate != historyDate) {
            dailyHistory.clear()
            historyDate = currentDate
            saveDailyHistory()
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.historyContainer)
        val btnDay = dialogView.findViewById<Button>(R.id.btnHistoryDay)
        val btnMonth = dialogView.findViewById<Button>(R.id.btnHistoryMonth)
        val btnYear = dialogView.findViewById<Button>(R.id.btnHistoryYear)
        container.removeAllViews()

        fun showEntries(entries: List<Pair<String, String>>) {
            container.removeAllViews()
            if (entries.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "No records"
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.textPrimary, theme))
                    setPadding(16, 16, 16, 16)
                })
            } else {
                entries.forEach { (id, nameAndTime) ->
                    container.addView(Button(this).apply {
                        text = "$id - $nameAndTime"
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 8 }
                        setTextColor(resources.getColor(R.color.buttonText, theme))
                        setBackgroundTintList(resources.getColorStateList(R.color.buttonBackground, theme))
                        setOnClickListener {
                            // nameAndTime contains "name (yyyy-MM-dd HH:mm:ss)" - extract name and timestamp
                            val timePart = nameAndTime.substringAfterLast("(").removeSuffix(")")
                            val name = nameAndTime.substringBeforeLast(" (")
                            // Attempt to print stored receipt (offline) using id and timestamp
                            reprintFromStoredHistory(id, timePart, name)
                        }
                    })
                }
            }
        }

        // Button behaviors: Day -> show today's entries from dailyHistory
        btnDay.setOnClickListener {
            // Allow the user to pick any date; default to today
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH)
            val day = cal.get(Calendar.DAY_OF_MONTH)
            DatePickerDialog(this, { _, y, m, d ->
                val picked = String.format(Locale.getDefault(), "%04d-%02d-%02d", y, m + 1, d)
                // Filter fullHistory for entries matching the picked date
                val filtered = fullHistory.mapNotNull { raw ->
                    try {
                        val obj = JSONObject(raw)
                        val ts = obj.optString("timestamp")
                        if (ts.startsWith(picked)) {
                            val id = obj.optString("id")
                            val name = obj.optString("name")
                            id to "$name (${ts.substring(0, 19)})"
                        } else null
                    } catch (e: Exception) { null }
                }
                showEntries(filtered)
            }, year, month, day).show()
        }

        // Month -> show entries from fullHistory that match selected month-year
        btnMonth.setOnClickListener {
            // show a simple dialog to pick month and year
            val months = monthOptions
            val monthPicker = Spinner(this)
            monthPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, months).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val yearNow = Calendar.getInstance().get(Calendar.YEAR)
            val years = (yearNow - 5..yearNow).map { it.toString() }
            val yearPicker = Spinner(this)
            yearPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, years).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val pickView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(monthPicker, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(yearPicker, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }

            AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("Select Month and Year")
                .setView(pickView)
                .setPositiveButton("Show") { _, _ ->
                    val m = months[monthPicker.selectedItemPosition]
                    val y = years[yearPicker.selectedItemPosition]
                    val filtered = fullHistory.mapNotNull { raw ->
                        try {
                            val obj = JSONObject(raw)
                            val ts = obj.optString("timestamp")
                            if (ts.startsWith("$y-")) {
                                val monthStr = ts.substring(5, 7).toIntOrNull() ?: 0
                                val monthName = if (monthStr in 1..12) monthOptions[monthStr - 1] else ""
                                if (monthName == m) {
                                    val id = obj.optString("id")
                                    val name = obj.optString("name")
                                    id to "$name (${ts.substring(0, 19)})"
                                } else null
                            } else null
                        } catch (e: Exception) { null }
                    }
                    showEntries(filtered)
                }
                .setNegativeButton("Cancel", null)
                .show()
                .apply { getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(resources.getColor(R.color.button_save_color, theme)) }
        }

        // Year -> show entries from fullHistory that match a selected year
        btnYear.setOnClickListener {
            val yearNow = Calendar.getInstance().get(Calendar.YEAR)
            val years = (yearNow - 5..yearNow).map { it.toString() }
            val yearPicker = Spinner(this)
            yearPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, years).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("Select Year")
                .setView(yearPicker)
                .setPositiveButton("Show") { _, _ ->
                    val y = years[yearPicker.selectedItemPosition]
                    val filtered = fullHistory.mapNotNull { raw ->
                        try {
                            val obj = JSONObject(raw)
                            val ts = obj.optString("timestamp")
                            if (ts.startsWith("$y-")) {
                                val id = obj.optString("id")
                                val name = obj.optString("name")
                                id to "$name (${ts.substring(0, 19)})"
                            } else null
                        } catch (e: Exception) { null }
                    }
                    showEntries(filtered)
                }
                .setNegativeButton("Cancel", null)
                .show()
                .apply { getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(resources.getColor(R.color.button_save_color, theme)) }
        }

        // Initially show today's entries
        btnDay.performClick()

        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("History")
            .setView(dialogView)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
            .apply { getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(resources.getColor(R.color.textPrimary, theme)) }
    }

    private fun reprintHistoryEntry(customerId: String, customerName: String) {
        if (selectedStreet == null || selectedMonth == null) {
            Toast.makeText(this, "Please select a street and month first", Toast.LENGTH_SHORT).show()
            return
        }

        progressDialog = ProgressDialog(this).apply {
            setMessage("Fetching previous reading for $customerId...")
            setCancelable(false)
            setCustomStyle()
            show()
        }

        fetchPreviousReading(customerId, selectedStreet!!, selectedMonth!!) { sheetPresentReading, _, _, fetchedRemarks, previousReading, usage, bill ->
            progressDialog.dismiss()
            if (sheetPresentReading == null || previousReading == null || usage == null || bill == null) {
                Toast.makeText(this@MainActivity, "Failed to fetch previous record for $customerId in $selectedStreet for $selectedMonth", Toast.LENGTH_LONG).show()
                return@fetchPreviousReading
            }

            val afterDueFee = (bill * 0.15).roundToLong().toDouble()
            val afterDue = bill + afterDueFee
            val calendar = Calendar.getInstance()
            val monthIndex = monthOptions.indexOf(selectedMonth!!)
            calendar.set(Calendar.MONTH, monthIndex + 1)
            calendar.set(Calendar.DAY_OF_MONTH, 25)
            val dueDate = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(calendar.time)

            val resultText = """
                -----------------------------------
                BOLO MULTIPURPOSE COOPERATIVE
                Rizal Street, Bolo, Bauan, Batangas
                Tel. No. 043-233-1084
                Email: brgybolowater@gmail.com
                -----------------------------------
                Customer: $customerName
                ID: $customerId
                Street: $selectedStreet
                Month: $selectedMonth
                Due: $dueDate
                After Due: PHP $afterDue
                Prev: $previousReading m³
                Pres: $sheetPresentReading m³
                Usage: $usage m³
                ---------
                BILL: PHP $bill
                ---------
                Garbage: PHP 70-100
                Remarks: $fetchedRemarks
                -----------------------------------
            """.trimIndent()

            lastResult = resultText
            btnPrint.isEnabled = true
            printReceipt(resultText)
        }
    }

    private fun reprintFromStoredHistory(customerId: String, timeString: String, customerNameFallback: String) {
        // Find the entry matching id and timestamp (match prefix)
        val match = fullHistory.asSequence().mapNotNull {
            try { JSONObject(it) } catch (e: Exception) { null }
        }.firstOrNull { obj ->
            val id = obj.optString("id")
            val ts = obj.optString("timestamp")
            id.equals(customerId, ignoreCase = true) && ts.startsWith(timeString)
        }

        if (match != null) {
            val receipt = match.optString("receipt", null)
            if (!receipt.isNullOrEmpty()) {
                lastResult = receipt
                btnPrint.isEnabled = true
                printReceipt(receipt)
                Toast.makeText(this, "Reprinted from saved history.", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Fallback: perform online reprint if available
        reprintHistoryEntry(customerId, customerNameFallback)
    }

    // Persist daily history list to SharedPreferences
    private fun saveDailyHistory() {
        val editor = sharedPreferences.edit()
        editor.putString("historyDate", historyDate)
        editor.putString("dailyHistory", dailyHistory.joinToString("|") { "${it.first},${it.second}" })
        editor.apply()
    }

    private fun loadDailyHistory() {
        historyDate = sharedPreferences.getString("historyDate", historyDate) ?: historyDate
        val historyString = sharedPreferences.getString("dailyHistory", null)
        if (historyString != null) {
            dailyHistory.clear()
            historyString.split("|").forEach { entry ->
                val parts = entry.split(",")
                if (parts.size == 2) {
                    dailyHistory.add(Pair(parts[0], parts[1]))
                }
            }
        }
    }

    private fun getPreviousMonth(month: String): String {
        val monthIndex = monthOptions.indexOf(month)
        return if (monthIndex == 0) {
            monthOptions[11]
        } else {
            monthOptions[monthIndex - 1]
        }
    }

    private fun cacheCustomerIds() {
        val editor = sharedPreferences.edit()
        editor.putString("customerIds", existingCustomerIds.joinToString(","))
        editor.apply()
    }

    private fun loadCachedCustomerIds() {
        val cachedIds = sharedPreferences.getString("customerIds", null)
        if (cachedIds != null) {
            existingCustomerIds.clear()
            existingCustomerIds.addAll(cachedIds.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            Log.d("WaterMeter", "Loaded cached customer IDs: $existingCustomerIds")
        }
    }

    private fun savePendingSubmissions() {
        val editor = sharedPreferences.edit()
        editor.putString("pendingSubmissions", pendingSubmissions.joinToString("|") { it.toString() })
        editor.apply()
    }

    private fun loadPendingSubmissions() {
        val pendingData = sharedPreferences.getString("pendingSubmissions", null)
        if (pendingData != null) {
            pendingSubmissions.clear()
            pendingData.split("|").forEach {
                if (it.isNotEmpty()) {
                    pendingSubmissions.add(JSONObject(it))
                }
            }
            Log.d("WaterMeter", "Loaded pending submissions: $pendingSubmissions")
        }
    }

    private fun saveLastCustomerId() {
        val editor = sharedPreferences.edit()
        editor.putString("lastCustomerId", lastCustomerId)
        editor.apply()
    }

    private fun loadLastCustomerId() {
        lastCustomerId = sharedPreferences.getString("lastCustomerId", null)
    }

    private fun saveNotepadContent() {
        val editor = sharedPreferences.edit()
        editor.putString("notepadContent", notepadContent)
        editor.apply()
    }

    private fun syncPendingSubmissions() {
        if (pendingSubmissions.isEmpty()) return

        val iterator = pendingSubmissions.iterator()
        while (iterator.hasNext()) {
            val jsonObject = iterator.next()
            val request = object : StringRequest(
                Method.POST,
                sheetUrl,
                { response ->
                    Log.d("WaterMeter", "Sync POST Success Response: '$response'")
                    if (response.trim() == "Success") {
                        Toast.makeText(this@MainActivity, "Synced pending data to Google Sheets", Toast.LENGTH_SHORT).show()
                        iterator.remove()
                        savePendingSubmissions()
                    }
                },
                { error ->
                    Log.e("WaterMeter", "Sync POST Error: ${error.message}")
                }
            ) {
                override fun getBody(): ByteArray {
                    return jsonObject.toString().toByteArray(Charsets.UTF_8)
                }

                override fun getBodyContentType(): String {
                    return "application/json; charset=utf-8"
                }
            }

            request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
            requestQueue.add(request)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hideColumnsForStreetAndMonth(street: String, month: String) {
        if (!isNetworkAvailable()) {
            Log.d("WaterMeter", "No internet to hide columns for $street, $month")
            return
        }

        val url = "$sheetUrl?action=hideColumns&street=$street&month=$month"
        val request = StringRequest(Request.Method.GET, url,
            { response ->
                Log.d("WaterMeter", "Hide columns response: $response")
            },
            { error ->
                Log.e("WaterMeter", "Error hiding columns: ${error.message}")
            })

        request.retryPolicy = DefaultRetryPolicy(30000, 3, 1.5f)
        requestQueue.add(request)
    }

    // Load streets from SharedPreferences; if none, initialize with defaults
    private fun loadStreets() {
        val raw = sharedPreferences.getString("streets", null)
        if (!raw.isNullOrEmpty()) {
            try {
                val array = JSONArray(raw)
                streetList.clear()
                for (i in 0 until array.length()) {
                    streetList.add(array.getString(i))
                }
            } catch (e: Exception) {
                Log.e("WaterMeter", "Failed to load streets: ${e.message}")
                initDefaultStreets()
            }
        } else {
            initDefaultStreets()
        }
    }

    private fun initDefaultStreets() {
        streetList.clear()
        streetList.addAll(listOf("Rizal 1", "Rizal 2", "Manigbas", "Legaspi 1", "Legaspi 2", "Gen Luna 1", "Gen Luna 2", "Balintawak"))
        saveStreets()
    }

    private fun saveStreets() {
        try {
            val array = JSONArray()
            streetList.forEach { array.put(it) }
            sharedPreferences.edit().putString("streets", array.toString()).apply()
        } catch (e: Exception) {
            Log.e("WaterMeter", "Failed to save streets: ${e.message}")
        }
    }

    private fun showAddStreetDialog(adapter: ArrayAdapter<String>) {
        val input = EditText(this)
        input.hint = getString(R.string.add_street_hint)

        val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(getString(R.string.add_street))
            .setView(input)
            .setPositiveButton(getString(R.string.add)) { d, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.street_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.length > 64) {
                    Toast.makeText(this, getString(R.string.street_too_long), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Duplicate check (case-insensitive)
                if (streetList.any { it.equals(name, ignoreCase = true) }) {
                    Toast.makeText(this, getString(R.string.street_exists), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                streetList.add(name)
                saveStreets()
                adapter.notifyDataSetChanged()
                spinnerStreet.setSelection(streetList.size - 1)
                Toast.makeText(this, getString(R.string.street_added), Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(resources.getColor(R.color.button_save_color, theme))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(resources.getColor(R.color.button_cancel_color, theme))
    }

    // Fetch available sheet names from the server (Apps Script should support action=listSheets)
    private fun fetchAvailableStreets(callback: (List<String>?) -> Unit) {
        try {
            val url = "$sheetUrl?action=listSheets"
            val request = StringRequest(Request.Method.GET, url,
                { response ->
                    try {
                        // Expecting a JSON array of sheet names (e.g. ["Rizal 1","Sinigwilasan",...])
                        val arr = JSONArray(response)
                        val result = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val name = arr.getString(i).trim()
                            if (name.isNotEmpty()) result.add(name)
                        }
                        Log.d("WaterMeter", "Fetched sheet list from server: $result")
                        callback(result)
                    } catch (je: Exception) {
                        Log.e("WaterMeter", "Failed to parse sheet list response: ${je.message}")
                        // Attempt to parse as object with error or sheets field
                        try {
                            val obj = JSONObject(response)
                            if (obj.has("error")) {
                                Log.e("WaterMeter", "Server returned error fetching sheets: ${obj.optString("error")}")
                                callback(null)
                            } else if (obj.has("sheets")) {
                                val arr2 = obj.getJSONArray("sheets")
                                val result = mutableListOf<String>()
                                for (i in 0 until arr2.length()) {
                                    val name = arr2.getString(i).trim()
                                    if (name.isNotEmpty()) result.add(name)
                                }
                                callback(result)
                            } else {
                                callback(null)
                            }
                        } catch (e2: Exception) {
                            Log.e("WaterMeter", "Failed to parse sheet list fallback: ${e2.message}")
                            callback(null)
                        }
                    }
                }, { error ->
                    Log.e("WaterMeter", "Error fetching available streets: ${error.message}")
                    callback(null)
                })

            request.retryPolicy = DefaultRetryPolicy(10000, 2, 1.5f)
            requestQueue.add(request)
        } catch (e: Exception) {
            Log.e("WaterMeter", "fetchAvailableStreets exception: ${e.message}")
            callback(null)
        }
    }
}
