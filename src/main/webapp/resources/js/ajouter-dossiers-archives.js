(function () {
    function btkGetEl(id) {
        var exact = document.getElementById(id);
        if (exact) {
            return exact;
        }
        return document.querySelector('[id$="' + id + '"]');
    }

    function btkDocs() {
        if (!window.BTKUploadState) {
            window.BTKUploadState = { docs: [] };
        }
        return window.BTKUploadState.docs;
    }

    function btkEscapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function btkOpenUploadDialog(btn) {
        if (!btn) {
            return false;
        }

        var label = btn.getAttribute('data-doc') || '';
        var targetId = btn.getAttribute('data-target') || '';
        var nameInput = btkGetEl('dialogDocName');
        var targetInput = btkGetEl('dialogTarget');
        var copyInput = btkGetEl('dialogCopy');
        var nombreInput = btkGetEl('dialogNombre');
        var descInput = btkGetEl('dialogDesc');
        var fileInput = btkGetEl('dialogFile');
        var fileNameInput = btkGetEl('dialogFileName');

        if (nameInput) {
            nameInput.value = label;
        }
        if (targetInput) {
            targetInput.value = targetId;
        }
        if (copyInput) {
            copyInput.selectedIndex = 0;
        }
        if (nombreInput) {
            nombreInput.value = "";
        }
        if (descInput) {
            descInput.value = "";
        }
        if (fileInput) {
            fileInput.value = "";
        }
        if (fileNameInput) {
            fileNameInput.value = "";
        }

        btkShowUploadDialog();
        return false;
    }

    function btkShowUploadDialog() {
        var overlay = btkGetEl('uploadDialogOverlay');
        if (overlay) {
            overlay.classList.add('is-open');
        }
    }

    function btkCloseUploadDialog() {
        var overlay = btkGetEl('uploadDialogOverlay');
        if (overlay) {
            overlay.classList.remove('is-open');
        }
        return false;
    }

    function btkOverlayClick(event) {
        if (!event) {
            return false;
        }
        if (event.target === btkGetEl('uploadDialogOverlay')) {
            return btkCloseUploadDialog();
        }
        return false;
    }

    function btkSyncDialogFile(input) {
        if (!input) {
            return;
        }

        var fileName = '';
        if (input.files && input.files.length) {
            fileName = input.files[0].name || '';
        } else if (input.value) {
            fileName = input.value.split('\\').pop();
        }

        var fileNameInput = btkGetEl('dialogFileName');
        if (fileNameInput) {
            fileNameInput.value = fileName;
        }

        var targetInput = btkGetEl('dialogTarget');
        if (!targetInput || !targetInput.value) {
            return;
        }

        var targetField = btkGetEl(targetInput.value);
        if (targetField) {
            targetField.value = fileName;
        }
    }

    function btkConfirmDialog() {
        var labelInput = btkGetEl('dialogDocName');
        var copyInput = btkGetEl('dialogCopy');
        var nombreInput = btkGetEl('dialogNombre');
        var descInput = btkGetEl('dialogDesc');
        var fileInput = btkGetEl('dialogFile');
        var targetInput = btkGetEl('dialogTarget');

        var label = labelInput ? (labelInput.value || '') : '';
        var copie = copyInput ? (copyInput.value || '') : '';
        var nombre = nombreInput ? (nombreInput.value || '') : '';
        var desc = descInput ? (descInput.value || '') : '';
        var targetId = targetInput ? (targetInput.value || '') : '';
        var fileName = '';

        if (fileInput && fileInput.files && fileInput.files.length) {
            fileName = fileInput.files[0].name || '';
        }

        if (!label && !fileName) {
            return false;
        }

        if (targetId) {
            var targetField = btkGetEl(targetId);
            if (targetField) {
                targetField.value = fileName || label;
            }
        }

        btkDocs().push({
            label: label,
            copie: copie,
            nombre: nombre,
            desc: desc,
            file: fileName
        });

        btkRenderDocs();
        btkCloseUploadDialog();

        return false;
    }

    function btkRenderDocs() {
        var docs = btkDocs();
        var body = btkGetEl('docsBody');
        if (!body) {
            return;
        }

        if (!docs.length) {
            body.innerHTML = '<tr id="docsEmptyRow"><td colspan="6" class="doc-empty">Aucun document ajouté.</td></tr>';
            return;
        }

        body.innerHTML = docs.map(function (doc, i) {
            return '<tr>' +
                '<td>' + btkEscapeHtml(doc.label || '-') + '</td>' +
                '<td>' + btkEscapeHtml(doc.copie || '-') + '</td>' +
                '<td>' + btkEscapeHtml(doc.nombre || '-') + '</td>' +
                '<td>' + btkEscapeHtml(doc.desc || '-') + '</td>' +
                '<td>' + btkEscapeHtml(doc.file || '-') + '</td>' +
                '<td><button type="button" class="doc-delete" onclick="btkRemoveDoc(' + i + ')">X</button></td>' +
                '</tr>';
        }).join('');
    }

    function btkRemoveDoc(index) {
        var docs = btkDocs();
        if (index < 0 || index >= docs.length) {
            return false;
        }
        docs.splice(index, 1);
        btkRenderDocs();
        return false;
    }

    window.btkGetEl = btkGetEl;
    window.btkOpenUploadDialog = btkOpenUploadDialog;
    window.btkShowUploadDialog = btkShowUploadDialog;
    window.btkCloseUploadDialog = btkCloseUploadDialog;
    window.btkOverlayClick = btkOverlayClick;
    window.btkSyncDialogFile = btkSyncDialogFile;
    window.btkConfirmDialog = btkConfirmDialog;
    window.btkRemoveDoc = btkRemoveDoc;
})();
