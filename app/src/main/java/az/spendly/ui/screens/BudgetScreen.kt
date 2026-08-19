/**
 * The 'Aylıq rasxod' plan for one month, plus the planned income rows from
 * 'BÜDCƏ İCMALI'!C11:C12. Actual figures are derived, never typed.
 */
package az.spendly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.spendly.domain.BudgetLine
import az.spendly.domain.CategoryDef
import az.spendly.domain.CategoryKind
import az.spendly.domain.FinanceData
import az.spendly.domain.MonthKey
import az.spendly.domain.TransactionType
import az.spendly.domain.budgetGroups
import az.spendly.domain.categoriesOfType
import az.spendly.domain.categoryUsage
import az.spendly.domain.formatAZN
import az.spendly.domain.formatMonth
import az.spendly.domain.formatSignedAZN
import az.spendly.domain.insights.KIND_LABEL
import az.spendly.domain.plannedIncomeRows
import az.spendly.domain.summarise
import az.spendly.ui.components.EmptyState
import az.spendly.ui.components.MoneyRow
import az.spendly.ui.components.RowCard
import az.spendly.ui.components.RowDivider
import az.spendly.ui.components.SectionHeader
import az.spendly.ui.dialogs.BudgetLineDialog
import az.spendly.ui.dialogs.CategoryDialog
import az.spendly.ui.dialogs.IncomePlanDialog
import az.spendly.ui.theme.spendlyColors

@Composable
fun BudgetScreen(
    data: FinanceData,
    month: MonthKey,
    onApplyTemplate: (MonthKey) -> Unit,
    onUpsertLine: (BudgetLine, Boolean) -> Unit,
    onRemoveLine: (String) -> Unit,
    onSetIncomePlan: (MonthKey, Map<String, Double>) -> Unit,
    onClearMonthPlan: (MonthKey) -> Unit,
    onResetAll: () -> Unit,
    onAddCategory: (String, TransactionType, CategoryKind?) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onSetCategoryKind: (String, CategoryKind?) -> Unit,
    onRemoveCategory: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors

    var editingLine by remember { mutableStateOf<BudgetLine?>(null) }
    var addingLine by remember { mutableStateOf(false) }
    var editingIncome by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Pair<CategoryDef?, TransactionType>?>(null) }

    val groups = budgetGroups(data, month)
    val summary = summarise(data, month)
    val plan = data.incomePlans.firstOrNull { it.month == month }
    val incomeCategories = categoriesOfType(data, TransactionType.INCOME)
    val incomeRows = plannedIncomeRows(incomeCategories, plan?.amounts ?: emptyMap())

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        /* --- planned income ------------------------------------------- */
        item {
            Column {
                SectionHeader(
                    title = "Planlaşdırılan gəlir",
                    action = {
                        TextButton(onClick = { editingIncome = true }) { Text("Dəyiş") }
                    },
                )
                RowCard {
                    incomeRows.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        MoneyRow(
                            title = row.category,
                            meta = if (row.orphaned) "kateqoriya silinib" else null,
                            amount = formatAZN(row.planned),
                        )
                    }
                    if (incomeRows.isEmpty()) {
                        Text(
                            text = "Hələ gəlir kateqoriyası yoxdur. Aşağıdan əlavə edin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textFaint,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    RowDivider()
                    MoneyRow(
                        title = "Cəmi",
                        meta = null,
                        amount = formatAZN(summary.plannedIncome),
                    )
                }
            }
        }

        /* --- planned expenses ------------------------------------------ */
        item {
            Column {
                SectionHeader(
                    title = "Planlaşdırılan xərclər",
                    action = if (groups.isNotEmpty()) {
                        { TextButton(onClick = { addingLine = true }) { Text("Sətir əlavə et") } }
                    } else {
                        null
                    },
                )

                if (groups.isEmpty()) {
                    RowCard {
                        EmptyState(
                            title = "${formatMonth(month)} üçün plan yoxdur",
                            body = "Keçən ayın planını köçürün və ya sıfırdan başlayın.",
                            action = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onApplyTemplate(month) }) {
                                        Text("Planı köçür")
                                    }
                                    OutlinedButton(onClick = { addingLine = true }) {
                                        Text("Sətir əlavə et")
                                    }
                                }
                            },
                        )
                    }
                } else {
                    RowCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surfaceInset)
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HeadCell("Kateqoriya", Modifier.weight(1f))
                            HeadCell("Plan", Modifier.weight(0.5f), end = true)
                            HeadCell("Faktiki", Modifier.weight(0.5f), end = true)
                            HeadCell("Qalıq", Modifier.weight(0.5f), end = true)
                        }

                        groups.forEach { group ->
                            RowDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = group.category,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.text,
                                    modifier = Modifier.weight(1f),
                                )
                                NumCell(formatAZN(group.planned), Modifier.weight(0.5f))
                                NumCell(
                                    formatAZN(group.actual),
                                    Modifier.weight(0.5f),
                                    color = colors.textMuted,
                                )
                                NumCell(
                                    formatSignedAZN(group.variance),
                                    Modifier.weight(0.5f),
                                    color = if (group.variance < 0) colors.negative else colors.text,
                                )
                            }

                            group.lines.forEach { line ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { editingLine = line }
                                        .padding(
                                            start = 28.dp,
                                            end = 16.dp,
                                            top = 8.dp,
                                            bottom = 8.dp,
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = line.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        modifier = Modifier.weight(1f),
                                    )
                                    NumCell(formatAZN(line.planned), Modifier.weight(0.5f))
                                }
                            }

                            if (group.lines.isEmpty()) {
                                Text(
                                    text = "Planlaşdırılmadan xərclənib",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textFaint,
                                    modifier = Modifier.padding(
                                        start = 28.dp,
                                        end = 16.dp,
                                        bottom = 8.dp,
                                    ),
                                )
                            }
                        }

                        RowDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Cəmi",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.text,
                                modifier = Modifier.weight(1f),
                            )
                            NumCell(formatAZN(summary.plannedExpenses), Modifier.weight(0.5f))
                            NumCell(formatAZN(summary.actualExpenses), Modifier.weight(0.5f))
                            NumCell(
                                formatSignedAZN(summary.plannedExpenses - summary.actualExpenses),
                                Modifier.weight(0.5f),
                                color = if (summary.plannedExpenses - summary.actualExpenses < 0) {
                                    colors.negative
                                } else {
                                    colors.text
                                },
                            )
                        }
                    }
                }
            }
        }

        /* --- planned remainder ------------------------------------------ */
        item {
            Column {
                SectionHeader(title = "Planlaşdırılan qalıq")
                RowCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = formatSignedAZN(summary.plannedRemainder),
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (summary.plannedRemainder < 0) colors.negative else colors.text,
                        )
                        Text(
                            text = "planlaşdırılan gəlir ${formatAZN(summary.plannedIncome)} − " +
                                "planlaşdırılan xərc ${formatAZN(summary.plannedExpenses)}" +
                                if (summary.plannedRemainder < 0) {
                                    " · bu plan qazancdan çox xərcləyir"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        /* --- deletion ---------------------------------------------------- */
        val hasPlan = groups.any { it.lines.isNotEmpty() }
        if (hasPlan || data.transactions.isNotEmpty()) {
            item {
                DangerZone(
                    month = month,
                    hasPlan = hasPlan,
                    transactionCount = data.transactions.size,
                    onClearPlan = { onClearMonthPlan(month) },
                    onResetAll = onResetAll,
                )
            }
        }

        /* --- categories --------------------------------------------------- */
        item {
            Column {
                SectionHeader(
                    title = "Kateqoriyalar",
                    action = {
                        TextButton(
                            onClick = { editingCategory = null to TransactionType.EXPENSE },
                        ) { Text("Əlavə et") }
                    },
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CategoryList(
                        data = data,
                        type = TransactionType.EXPENSE,
                        title = "Xərc",
                        onSelect = { editingCategory = it to TransactionType.EXPENSE },
                        onAdd = { editingCategory = null to TransactionType.EXPENSE },
                    )
                    CategoryList(
                        data = data,
                        type = TransactionType.INCOME,
                        title = "Gəlir",
                        onSelect = { editingCategory = it to TransactionType.INCOME },
                        onAdd = { editingCategory = null to TransactionType.INCOME },
                    )
                }
            }
        }
    }

    if (addingLine || editingLine != null) {
        val line = editingLine
        BudgetLineDialog(
            data = data,
            line = line,
            onSave = { values ->
                onUpsertLine(
                    BudgetLine(
                        id = line?.id.orEmpty(),
                        month = month,
                        description = values.description,
                        category = values.category,
                        planned = values.planned,
                    ),
                    line == null,
                )
                addingLine = false
                editingLine = null
            },
            onDelete = line?.let {
                {
                    onRemoveLine(it.id)
                    editingLine = null
                }
            },
            onDismiss = {
                addingLine = false
                editingLine = null
            },
        )
    }

    if (editingIncome) {
        IncomePlanDialog(
            rows = incomeRows,
            amounts = plan?.amounts ?: emptyMap(),
            onSave = { amounts ->
                onSetIncomePlan(month, amounts)
                editingIncome = false
            },
            onDismiss = { editingIncome = false },
        )
    }

    editingCategory?.let { (category, type) ->
        CategoryDialog(
            data = data,
            category = category,
            type = type,
            onAdd = onAddCategory,
            onRename = onRenameCategory,
            onSetKind = onSetCategoryKind,
            onRemove = onRemoveCategory,
            onDismiss = { editingCategory = null },
        )
    }
}

@Composable
private fun HeadCell(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = spendlyColors.textFaint,
        modifier = modifier,
        textAlign = if (end) {
            androidx.compose.ui.text.style.TextAlign.End
        } else {
            androidx.compose.ui.text.style.TextAlign.Start
        },
    )
}

@Composable
private fun NumCell(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color ?: spendlyColors.text,
        modifier = modifier,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

/**
 * One side of the ledger's categories, each with what depends on it. The usage
 * count is shown because it is what decides whether a category can simply be
 * removed or has to be moved somewhere first.
 */
@Composable
private fun CategoryList(
    data: FinanceData,
    type: TransactionType,
    title: String,
    onSelect: (CategoryDef) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = spendlyColors
    val categories = categoriesOfType(data, type)

    RowCard {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textFaint,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )

        categories.forEach { category ->
            RowDivider()
            val usage = categoryUsage(data, category.name)
            val total = usage.transactions + usage.budgetLines

            MoneyRow(
                title = category.name,
                meta = listOfNotNull(
                    category.kind?.let { KIND_LABEL[it] },
                    if (total == 0) {
                        "istifadə olunmur"
                    } else {
                        listOfNotNull(
                            usage.transactions.takeIf { it > 0 }?.let { "$it əməliyyat" },
                            usage.budgetLines.takeIf { it > 0 }?.let { "$it büdcə sətri" },
                        ).joinToString(" · ")
                    },
                ).joinToString(" · "),
                amount = "Dəyiş",
                amountColor = colors.accent,
                onClick = { onSelect(category) },
            )
        }

        if (categories.isEmpty()) {
            Text(
                text = "Hələ kateqoriya yoxdur.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
                modifier = Modifier.padding(16.dp),
            )
        }

        RowDivider()
        Text(
            text = "+ Kateqoriya əlavə et",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.accent,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAdd)
                .padding(16.dp),
        )
    }
}

/**
 * Bulk deletion. Kept at the very bottom, visually quiet, and every action
 * needs a second tap — these remove data that cannot be recovered.
 */
@Composable
private fun DangerZone(
    month: MonthKey,
    hasPlan: Boolean,
    transactionCount: Int,
    onClearPlan: () -> Unit,
    onResetAll: () -> Unit,
) {
    val colors = spendlyColors
    var confirming by remember { mutableStateOf<String?>(null) }

    Column {
        SectionHeader(title = "Silmə")
        RowCard {
            if (hasPlan) {
                DangerRow(
                    title = "${formatMonth(month)} planını sil",
                    body = "Yalnız bu ayın planlaşdırılan sətirləri silinir, əməliyyatlara toxunulmur.",
                    label = if (confirming == "plan") "Təsdiqlə" else "Planı sil",
                    onClick = {
                        if (confirming == "plan") {
                            onClearPlan()
                            confirming = null
                        } else {
                            confirming = "plan"
                        }
                    },
                )
                RowDivider()
            }
            DangerRow(
                title = "Bütün məlumatları sil",
                body = "$transactionCount əməliyyat və bütün aylar üzrə planlar həmişəlik silinir.",
                label = if (confirming == "all") "Hər şeyi sil" else "Hamısını sil",
                onClick = {
                    if (confirming == "all") {
                        onResetAll()
                        confirming = null
                    } else {
                        confirming = "all"
                    }
                },
            )
        }
    }
}

@Composable
private fun DangerRow(title: String, body: String, label: String, onClick: () -> Unit) {
    val colors = spendlyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.text,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        TextButton(onClick = onClick) {
            Text(label, color = colors.negative)
        }
    }
}
