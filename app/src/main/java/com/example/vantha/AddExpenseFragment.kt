package com.example.vantha

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.vantha.api.ExpenseRequest
import com.example.vantha.api.RetrofitClient
import com.example.vantha.api.getCurrentDateISO8601
import com.example.vantha.databinding.FragmentAddExpenseBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AddExpenseFragment : Fragment() {

    private var _binding: FragmentAddExpenseBinding? = null
    private val binding get() = _binding!!
    private var selectedDate: Calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private lateinit var auth: FirebaseAuth

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddExpenseBinding.inflate(inflater, container, false)
        auth = FirebaseAuth.getInstance()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDatePicker()
        setupAddCategoryButton()
        setupSaveButton()
    }

    private fun setupDatePicker() {
        binding.inputDate.setText(dateFormat.format(selectedDate.time))

        binding.inputDate.setOnClickListener {
            val datePickerDialog = DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    selectedDate.set(year, month, dayOfMonth)
                    binding.inputDate.setText(dateFormat.format(selectedDate.time))
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
            )
            datePickerDialog.show()
        }
    }

    private fun setupAddCategoryButton() {
        binding.buttonAddCategory.setOnClickListener {
            val input = EditText(requireContext())
            input.hint = getString(R.string.button_add_category)

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.button_add_category))
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val newCategory = input.text.toString().trim()
                    if (newCategory.isNotEmpty()) {
                        addCategoryToSpinner(newCategory)
                        Toast.makeText(requireContext(), "Category added: $newCategory", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun addCategoryToSpinner(newCategory: String) {
        val currentCategories = resources.getStringArray(R.array.expense_categories).toMutableList()
        currentCategories.add(newCategory)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, currentCategories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCategory.adapter = adapter
        binding.spinnerCategory.setSelection(currentCategories.size - 1)
    }

    private fun setupSaveButton() {
        binding.buttonSaveExpense.setOnClickListener {
            if (validateInput()) {
                saveExpense()
            }
        }
    }

    private fun validateInput(): Boolean {
        val amountText = binding.editTextAmount.text.toString().trim()

        if (amountText.isEmpty()) {
            binding.editTextAmount.error = getString(R.string.error_empty_amount)
            binding.editTextAmount.requestFocus()
            return false
        }

        val amount = amountText.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            binding.editTextAmount.error = getString(R.string.error_invalid_amount)
            binding.editTextAmount.requestFocus()
            return false
        }

        return true
    }

    private fun saveExpense() {
        showLoading(true)

        val amount = binding.editTextAmount.text.toString().toDouble()
        val currency = binding.spinnerCurrency.selectedItem.toString()
        val category = binding.spinnerCategory.selectedItem.toString()
        val remark = binding.editTextDescription.text.toString().trim()

        val userId = auth.currentUser?.uid ?: ""

        val expenseRequest = ExpenseRequest(
            id = UUID.randomUUID().toString(),
            amount = amount,
            currency = currency,
            category = category,
            remark = remark,
            createdBy = userId,
            createdDate = getCurrentDateISO8601()
        )

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.expenseApiService.createExpense(
                    dbName = RetrofitClient.DB_NAME,
                    expense = expenseRequest
                )

                showLoading(false)

                if (response.isSuccessful) {
                    val savedExpense = response.body()

                    clearForm()

                    // Navigate to list immediately
                    (activity as? MainActivity)?.binding?.bottomNavigation?.selectedItemId = R.id.nav_expense_list

                    Toast.makeText(
                        requireContext(),
                        "Expense saved successfully!",
                        Toast.LENGTH_SHORT
                    ).show()

                    // ✅ AGGRESSIVE REFRESH: Multiple fast retries to catch DB replication ASAP
                    lifecycleScope.launch {
                        // Retry 6 times: 200ms, 400ms, 600ms, 800ms, 1200ms, 1600ms
                        val delays = listOf(200L, 200L, 200L, 200L, 400L, 400L)

                        delays.forEach { delayMs ->
                            delay(delayMs)

                            (activity as? MainActivity)?.let { mainActivity ->
                                mainActivity.supportFragmentManager.fragments.forEach { fragment ->
                                    if (fragment is ExpenseListFragment && fragment.isAdded) {
                                        fragment.refreshList()
                                    }
                                    if (fragment is HomeFragment && fragment.isAdded) {
                                        fragment.loadLastExpense()
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to save expense: ${response.code()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                showLoading(false)
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.buttonSaveExpense.isEnabled = !show
        binding.buttonSaveExpense.text = if (show) "Saving..." else getString(R.string.button_add_expense)
    }

    private fun clearForm() {
        binding.editTextAmount.text?.clear()
        binding.editTextDescription.text?.clear()
        binding.spinnerCurrency.setSelection(0)
        binding.spinnerCategory.setSelection(0)
        selectedDate = Calendar.getInstance()
        binding.inputDate.setText(dateFormat.format(selectedDate.time))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}