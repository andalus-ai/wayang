import 'package:flutter/material.dart';

import 'components.dart';
import 'tokens.dart';

/// Describes one presentation column in [WdsDataTable].
///
/// Sorting is controlled by the host: [onSort] receives the column index and
/// the next ascending state, while the host supplies the updated rows.
class WdsDataColumn<T> {
  const WdsDataColumn({
    required this.label,
    required this.cellBuilder,
    this.numeric = false,
    this.sortable = false,
    this.tooltip,
  });

  final String label;
  final Widget Function(BuildContext context, T row) cellBuilder;
  final bool numeric;
  final bool sortable;
  final String? tooltip;
}

/// A theme-aware, horizontally scrollable table with optional controlled
/// sorting, row selection and trailing row actions.
///
/// This widget intentionally does not own pagination, data fetching, sorting
/// algorithms or selection persistence.
class WdsDataTable<T> extends StatelessWidget {
  const WdsDataTable({
    super.key,
    required this.columns,
    required this.rows,
    this.rowKey,
    this.selectedRowKeys = const <Object>{},
    this.onSelectionChanged,
    this.onSort,
    this.sortColumnIndex,
    this.sortAscending = true,
    this.rowActionsBuilder,
    this.isLoading = false,
    this.emptyTitle = 'No records found',
    this.emptyDescription,
    this.emptyIcon = Icons.inbox_outlined,
    this.headingRowHeight = 52,
    this.dataRowMinHeight = 52,
    this.dataRowMaxHeight = 72,
    this.columnSpacing = 24,
    this.horizontalMargin = 20,
  }) : assert(columns.length > 0);

  final List<WdsDataColumn<T>> columns;
  final List<T> rows;
  final Object Function(T row)? rowKey;
  final Set<Object> selectedRowKeys;
  final ValueChanged<Set<Object>>? onSelectionChanged;
  final void Function(int columnIndex, bool ascending)? onSort;
  final int? sortColumnIndex;
  final bool sortAscending;
  final Widget Function(BuildContext context, T row)? rowActionsBuilder;
  final bool isLoading;
  final String emptyTitle;
  final String? emptyDescription;
  final IconData emptyIcon;
  final double headingRowHeight;
  final double dataRowMinHeight;
  final double dataRowMaxHeight;
  final double columnSpacing;
  final double horizontalMargin;

  @override
  Widget build(BuildContext context) {
    if (rows.isEmpty && !isLoading) {
      return WdsEmptyState(
        icon: emptyIcon,
        title: emptyTitle,
        description: emptyDescription,
      );
    }

    final selectable = rowKey != null && onSelectionChanged != null;
    final allSelected = selectable &&
        rows.isNotEmpty &&
        rows.every((row) => selectedRowKeys.contains(rowKey!(row)));
    final someSelected = selectable &&
        rows.any((row) => selectedRowKeys.contains(rowKey!(row))) &&
        !allSelected;

    final dataColumns = <DataColumn>[
      for (var index = 0; index < columns.length; index++)
        DataColumn(
          label: Text(columns[index].label),
          numeric: columns[index].numeric,
          tooltip: columns[index].tooltip,
          onSort: columns[index].sortable && onSort != null
              ? (columnIndex, ascending) => onSort!(columnIndex, ascending)
              : null,
        ),
      if (rowActionsBuilder != null)
        const DataColumn(label: SizedBox.shrink(), numeric: true),
    ];

    final dataRows = <DataRow>[
      for (final row in rows)
        DataRow(
          selected: selectable && selectedRowKeys.contains(rowKey!(row)),
          onSelectChanged: selectable
              ? (selected) {
                  final next = Set<Object>.from(selectedRowKeys);
                  final key = rowKey!(row);
                  if (selected == true) {
                    next.add(key);
                  } else {
                    next.remove(key);
                  }
                  onSelectionChanged!(next);
                }
              : null,
          cells: [
            for (final column in columns)
              DataCell(column.cellBuilder(context, row)),
            if (rowActionsBuilder != null)
              DataCell(rowActionsBuilder!(context, row)),
          ],
        ),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (isLoading) const LinearProgressIndicator(minHeight: 2),
        if (selectable && rows.isNotEmpty)
          Align(
            alignment: Alignment.centerLeft,
            child: Padding(
              padding: const EdgeInsets.only(
                left: WdsSpacing.md,
                top: WdsSpacing.xs,
                bottom: WdsSpacing.xs,
              ),
              child: TextButton.icon(
                onPressed: () => onSelectionChanged!(
                  allSelected
                      ? <Object>{}
                      : rows.map((row) => rowKey!(row)).toSet(),
                ),
                icon: Icon(
                  allSelected
                      ? Icons.deselect_rounded
                      : someSelected
                          ? Icons.indeterminate_check_box_outlined
                          : Icons.select_all_rounded,
                  size: 18,
                ),
                label: Text(allSelected ? 'Clear selection' : 'Select all'),
              ),
            ),
          ),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            columns: dataColumns,
            rows: dataRows,
            sortColumnIndex: sortColumnIndex,
            sortAscending: sortAscending,
            headingRowHeight: headingRowHeight,
            dataRowMinHeight: dataRowMinHeight,
            dataRowMaxHeight: dataRowMaxHeight,
            columnSpacing: columnSpacing,
            horizontalMargin: horizontalMargin,
            showCheckboxColumn: selectable,
          ),
        ),
      ],
    );
  }
}
