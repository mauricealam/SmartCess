package com.smartcess.app.features.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartcess.app.R
import com.smartcess.app.data.local.entity.RoutineEntity
import com.smartcess.app.ui.components.BannerKind
import com.smartcess.app.ui.components.SmartCessBanner
import com.smartcess.app.ui.components.SmartCessCard
import com.smartcess.app.ui.components.SmartCessConfirmDialog
import com.smartcess.app.ui.components.SmartCessListItem
import com.smartcess.app.ui.components.SmartCessPrimaryButton
import com.smartcess.app.ui.components.SmartCessSecondaryButton
import com.smartcess.app.ui.components.SmartCessTextField
import com.smartcess.app.ui.components.SmartCessTopBar

/** Routine Guardian entry: medication, delivery, recurring with completion tracking. */
@Composable
fun RoutinesScreen(
    onRoutine: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutinesViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val form by viewModel.form.collectAsState()
    var showForm by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SmartCessTopBar(title = stringResource(R.string.routines_title), onBack = onBack)
        SmartCessBanner(message = stringResource(R.string.routine_no_advice), kind = BannerKind.INFO)
        form.errorRes?.let {
            SmartCessBanner(
                title = stringResource(R.string.banner_error_title),
                message = stringResource(it),
                kind = BannerKind.ERROR,
            )
        }
        form.messageRes?.let {
            SmartCessBanner(message = stringResource(it), kind = BannerKind.INFO)
        }
        if (!showForm) {
            SmartCessPrimaryButton(
                text = stringResource(R.string.routines_add),
                onClick = { showForm = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            RoutineForm(
                form = form,
                onForm = viewModel::onForm,
                onDraft = viewModel::draftFromWords,
                onSave = { showSaveConfirm = true },
            )
        }
        if (items.isEmpty()) {
            Text(text = stringResource(R.string.routines_empty), style = MaterialTheme.typography.bodyLarge)
        } else {
            SmartCessCard {
                items.forEach { routine ->
                    val missed = routine.isMissed()
                    SmartCessListItem(
                        title = routine.title,
                        subtitle = routine.scheduleRule,
                        contentDescription = stringResource(
                            R.string.routine_item_desc,
                            routine.title,
                            routine.scheduleRule,
                        ) + if (missed) ". " + stringResource(R.string.routine_missed) else "",
                        onClick = { onRoutine(routine.id) },
                        trailing = {
                            if (missed) {
                                Text(
                                    text = stringResource(R.string.routine_missed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (showSaveConfirm) {
        SmartCessConfirmDialog(
            title = stringResource(R.string.routines_add),
            message = form.title,
            onConfirm = {
                showSaveConfirm = false
                showForm = false
                viewModel.confirmSave(onRoutine)
            },
            onDismiss = { showSaveConfirm = false },
        )
    }
}

@Composable
fun routineKindLabel(kind: String): String = when (kind) {
    RoutineEntity.KIND_MEDICATION -> stringResource(R.string.routine_kind_medication)
    RoutineEntity.KIND_DELIVERY -> stringResource(R.string.routine_kind_delivery)
    RoutineEntity.KIND_RECURRING -> stringResource(R.string.routine_kind_recurring)
    else -> stringResource(R.string.routine_kind_other)
}

@Composable
private fun RoutineForm(
    form: RoutineFormState,
    onForm: (RoutineFormState) -> Unit,
    onDraft: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.routines_add),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SmartCessTextField(
            value = form.describeText,
            onValueChange = { onForm(form.copy(describeText = it)) },
            label = stringResource(R.string.routine_describe_label),
            hint = stringResource(R.string.routine_describe_hint),
            singleLine = false,
            minLines = 2,
        )
        SmartCessSecondaryButton(
            text = stringResource(R.string.routine_draft_button),
            onClick = onDraft,
            enabled = form.describeText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        SmartCessTextField(
            value = form.title,
            onValueChange = { onForm(form.copy(title = it)) },
            label = stringResource(R.string.routine_title_label),
            error = form.fieldErrorRes?.let { stringResource(it) },
        )
        SmartCessCard {
            listOf(
                RoutineEntity.KIND_MEDICATION,
                RoutineEntity.KIND_DELIVERY,
                RoutineEntity.KIND_RECURRING,
                RoutineEntity.KIND_OTHER,
            ).forEach { kind ->
                SmartCessListItem(
                    title = routineKindLabel(kind),
                    onClick = { onForm(form.copy(kind = kind)) },
                    trailing = {
                        if (form.kind == kind) {
                            Text(
                                text = stringResource(R.string.option_selected),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }
        }
        SmartCessTextField(
            value = form.scheduleRule,
            onValueChange = { onForm(form.copy(scheduleRule = it)) },
            label = stringResource(R.string.routine_schedule_label),
        )
        SmartCessTextField(
            value = form.notes,
            onValueChange = { onForm(form.copy(notes = it)) },
            label = stringResource(R.string.routine_notes_label),
            singleLine = false,
            minLines = 2,
        )
        SmartCessPrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
