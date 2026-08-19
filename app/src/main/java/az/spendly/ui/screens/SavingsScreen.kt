/**
 * Yığım — the pots, what is in them, and every movement.
 *
 * The screen exists because a pot holds money that still exists. Setting money
 * aside is not spending it, so the figures here are a third flow rather than a
 * kind of expense — and the three totals at the top are what that distinction
 * looks like: what can be spent, what is put away, and the two together.
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.spendly.domain.FinanceData
import az.spendly.domain.MonthKey
import az.spendly.domain.PotRow
import az.spendly.domain.SavingsDirection
import az.spendly.domain.SavingsEntry
import az.spendly.domain.SavingsPot
import az.spendly.domain.SavingsSource
import az.spendly.domain.convertibleSavingTransactions
import az.spendly.domain.depositedFromIncome
import az.spendly.domain.depositedFromOutside
import az.spendly.domain.entriesInMonth
import az.spendly.domain.formatAZN
import az.spendly.domain.formatDayShort
import az.spendly.domain.formatMonth
import az.spendly.domain.potRows
import az.spendly.domain.savingsBalance
import az.spendly.domain.spendableBalance
import az.spendly.domain.totalHoldings
import az.spendly.ui.components.EmptyState
import az.spendly.ui.components.Meter
import az.spendly.ui.components.Micro
import az.spendly.ui.components.MoneyRow
import az.spendly.ui.components.RowCard
import az.spendly.ui.components.RowDivider
import az.spendly.ui.components.SectionHeader
import az.spendly.ui.dialogs.SavingsEntryDialog
import az.spendly.ui.dialogs.SavingsPotDialog
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors
import kotlin.math.roundToInt

@Composable
fun SavingsScreen(
    data: FinanceData,
    month: MonthKey,
    defaultDate: String,
    onAddPot: (String, Double?) -> Unit,
    onRenamePot: (String, String) -> Unit,
    onSetPotTarget: (String, Double?) -> Unit,
    onRemovePot: (String, String?) -> Unit,
    onAddEntry: (SavingsEntry) -> Unit,
    onUpdateEntry: (String, SavingsEntry) -> Unit,
    onRemoveEntry: (String) -> Unit,
    onConvertFromTransactions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors

    var editingPot by remember { mutableStateOf<SavingsPot?>(null) }
    var addingPot by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<SavingsEntry?>(null) }
    var addingEntry by remember { mutableStateOf(false) }
    var newEntryPot by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }

    val rows = potRows(data)
    val saved = savingsBalance(data.savingsEntries)
    val spendable = spendableBalance(data)
    val total = totalHoldings(data)
    val fromIncome = depositedFromIncome(data.savingsEntries, month)
    val fromOutside = depositedFromOutside(data.savingsEntries, month)
    val monthEntries = entriesInMonth(data.savingsEntries, month)
    val allEntries = data.savingsEntries.sortedByDescending { it.date }
    val listed = if (showAll) allEntries else monthEntries

    val convertible = convertibleSavingTransactions(data)

    fun openEntry(pot: String?) {
        newEntryPot = pot
        addingEntry = true
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        /* Savings recorded the old way, before pots existed. Offered rather
           than applied: it deletes transactions, and that is not a decision to
           make on somebody's behalf while they are looking elsewhere. */
        if (convertible.transactions.isNotEmpty()) {
            item {
                RowCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "${convertible.transactions.size} əməliyyat yığım kimi " +
                                "işarələnmiş kateqoriyalarda xərc kimi yazılıb — cəmi " +
                                "${formatAZN(convertible.total)}. Bunları qab hərəkətinə " +
                                "çevirsək, həmin pul xərc sayılmaqdan çıxar və yığım " +
                                "balansınıza keçər. Kateqoriya adları qab adı olacaq: " +
                                convertible.pots.joinToString(", ") + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text,
                        )
                        Button(onClick = onConvertFromTransactions) { Text("Yığıma köçür") }
                    }
                }
            }
        }

        /* --- where the money stands ---------------------------------- */
        item {
            Column {
                SectionHeader(title = "Harada dayanırsınız")
                RowCard {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Holding(
                            label = "Xərcləyə bilən",
                            value = formatAZN(spendable),
                            tone = if (spendable < 0) colors.negative else colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Holding(
                            label = "Yığım",
                            value = formatAZN(saved),
                            tone = colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Holding(
                            label = "Cəmi",
                            value = formatAZN(total),
                            tone = if (total < 0) colors.negative else colors.accent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    text = "${formatMonth(month)}: gəlirdən ${formatAZN(fromIncome)} kənara " +
                        "qoyulub" +
                        (if (fromOutside > 0) ", kənardan ${formatAZN(fromOutside)} gəlib" else "") +
                        ". Kənardan gələn pul gəlir hesabatlarınıza düşmür — o, heç vaxt " +
                        "xərcləyə biləcəyiniz tərəfdə olmayıb.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        /* --- the pots ------------------------------------------------ */
        item {
            Column {
                SectionHeader(
                    title = "Qablar",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { addingPot = true }) {
                                Text("Qab əlavə et")
                            }
                            if (rows.isNotEmpty()) {
                                Button(onClick = { openEntry(null) }) { Text("Qoy / götür") }
                            }
                        }
                    },
                )

                if (rows.isEmpty()) {
                    RowCard {
                        EmptyState(
                            title = "Hələ qab yoxdur",
                            body = "Bir hədəf adlandırın — ehtiyat fondu, avtomobil, nə " +
                                "olursa. Sonra ora qoyduğunuz hər məbləği qeyd edərsiniz.",
                            action = {
                                Button(onClick = { addingPot = true }) { Text("Qab əlavə et") }
                            },
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.forEach { row ->
                            Pot(
                                row = row,
                                onEdit = { row.pot?.let { editingPot = it } },
                                onMove = { openEntry(row.name) },
                            )
                        }
                    }
                }
            }
        }

        /* --- every movement ------------------------------------------ */
        item {
            Column {
                SectionHeader(
                    title = "Hərəkətlər",
                    action = if (allEntries.size > monthEntries.size) {
                        {
                            TextButton(onClick = { showAll = !showAll }) {
                                Text(if (showAll) formatMonth(month) else "Bütün tarixçə")
                            }
                        }
                    } else {
                        null
                    },
                )

                if (listed.isEmpty()) {
                    RowCard {
                        EmptyState(
                            title = "${formatMonth(month)} üçün hərəkət yoxdur",
                            body = "Kənara qoyduğunuz və ya qabdan götürdüyünüz hər məbləği " +
                                "burada qeyd edin.",
                        )
                    }
                } else {
                    RowCard {
                        listed.forEachIndexed { index, entry ->
                            if (index > 0) RowDivider()
                            val deposit = entry.direction == SavingsDirection.IN
                            MoneyRow(
                                leading = formatDayShort(entry.date),
                                title = entry.pot,
                                meta = (
                                    if (deposit) {
                                        if (entry.source == SavingsSource.EXTERNAL) {
                                            "kənardan"
                                        } else {
                                            "gəlirdən"
                                        }
                                    } else {
                                        "götürüldü"
                                    }
                                    ) + (entry.note?.let { " · $it" } ?: ""),
                                amount = (if (deposit) "+" else "−") + formatAZN(entry.amount),
                                amountColor = if (deposit) colors.positive else colors.text,
                                onClick = { editingEntry = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    if (addingPot || editingPot != null) {
        val pot = editingPot
        SavingsPotDialog(
            data = data,
            pot = pot,
            onAdd = onAddPot,
            onRename = onRenamePot,
            onSetTarget = onSetPotTarget,
            onRemove = onRemovePot,
            onDismiss = {
                addingPot = false
                editingPot = null
            },
        )
    }

    if (addingEntry || editingEntry != null) {
        val entry = editingEntry
        SavingsEntryDialog(
            data = data,
            entry = entry,
            defaultDate = defaultDate,
            defaultPot = if (entry == null) newEntryPot else null,
            onSave = { values ->
                if (entry == null) onAddEntry(values) else onUpdateEntry(entry.id, values)
                addingEntry = false
                editingEntry = null
            },
            onDelete = entry?.let {
                {
                    onRemoveEntry(it.id)
                    editingEntry = null
                }
            },
            onDismiss = {
                addingEntry = false
                editingEntry = null
            },
        )
    }
}

@Composable
private fun Holding(
    label: String,
    value: String,
    tone: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Micro(label)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = tone,
        )
    }
}

@Composable
private fun Pot(row: PotRow, onEdit: () -> Unit, onMove: () -> Unit) {
    val colors = spendlyColors

    RowCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!row.orphaned) Modifier.clickable(onClick = onEdit) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                if (row.orphaned) {
                    Text(
                        text = "silinib",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surfaceSunken)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = formatAZN(row.balance),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                )
            }

            val target = row.target
            val progress = row.progress
            if (target != null && progress != null) {
                Meter(row.balance, target)
                Text(
                    text = "hədəfin ${(progress * 100).roundToInt()}%-i · " +
                        "${formatAZN(target)} hədəf" +
                        // At zero the remaining amount is the target again, and
                        // saying the same figure twice reads as a mistake.
                        if (row.balance > 0 && row.balance < target) {
                            " · ${formatAZN(target - row.balance)} qalıb"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            } else {
                Text(
                    text = "${row.entries} qeyd · hədəf təyin edilməyib",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }

            if (!row.orphaned) {
                OutlinedButton(onClick = onMove) { Text("Bu qaba qoy / götür") }
            }
        }
    }
}
