(function () {
    function btkState() {
        if (!window.BTKUploadState) {
            window.BTKUploadState = { docs: [], counter: 0 };
        }
        if (typeof window.BTKUploadState.counter !== 'number') {
            window.BTKUploadState.counter = 0;
        }
        return window.BTKUploadState;
    }

    function btkGetEl(id) {
        var exact = document.getElementById(id);
        if (exact) {
            return exact;
        }
        return document.querySelector('[id$="' + id + '"]');
    }

    function btkDocs() {
        return btkState().docs;
    }

    function btkSyncDocsPayload() {
        var payload = btkGetEl('docsPayload');
        if (payload) {
            payload.value = JSON.stringify(btkDocs());
        }
    }

    function btkEscapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function btkCurrentDateTime() {
        var now = new Date();
        var dd = String(now.getDate()).padStart(2, '0');
        var mm = String(now.getMonth() + 1).padStart(2, '0');
        var yyyy = now.getFullYear();
        var hh = String(now.getHours()).padStart(2, '0');
        var min = String(now.getMinutes()).padStart(2, '0');
        return dd + '/' + mm + '/' + yyyy + ' ' + hh + ':' + min;
    }

    function btkDocsLayout() {
        var body = btkGetEl('docsBody');
        if (!body) {
            return 'legacy';
        }
        return body.getAttribute('data-layout') || 'legacy';
    }

    function btkResolveCreator() {
        var body = btkGetEl('docsBody');
        if (body) {
            var createdBy = body.getAttribute('data-created-by');
            if (createdBy) {
                return createdBy;
            }
        }
        if (window.BTKCurrentDocumentCreator) {
            return String(window.BTKCurrentDocumentCreator);
        }
        return 'unknown';
    }

    function btkGetDocFileInput(doc) {
        if (!doc || !doc.fileKey) {
            return null;
        }
        var filesContainer = btkGetEl('docsFilesContainer');
        if (!filesContainer) {
            return null;
        }
        return filesContainer.querySelector('[name="' + doc.fileKey + '"]');
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
            nombreInput.value = '';
        }
        if (descInput) {
            descInput.value = '';
        }
        if (fileInput) {
            fileInput.value = '';
        }
        if (fileNameInput) {
            fileNameInput.value = '';
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

    function btkIsUploadDialogOpen() {
        var overlay = btkGetEl('uploadDialogOverlay');
        return !!(overlay && overlay.classList.contains('is-open'));
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

    function btkConfirmDialog(event) {
        if (event) {
            if (typeof event.preventDefault === 'function') {
                event.preventDefault();
            }
            if (typeof event.stopPropagation === 'function') {
                event.stopPropagation();
            }
        }

        var labelInput = btkGetEl('dialogDocName');
        var copyInput = btkGetEl('dialogCopy');
        var nombreInput = btkGetEl('dialogNombre');
        var descInput = btkGetEl('dialogDesc');
        var fileInput = btkGetEl('dialogFile');
        var targetInput = btkGetEl('dialogTarget');
        var fileSlot = btkGetEl('dialogFileSlot');
        var filesContainer = btkGetEl('docsFilesContainer');

        var docLabel = labelInput ? (labelInput.value || '') : '';
        var copie = copyInput ? (copyInput.value || '') : '';
        var nombre = nombreInput ? (nombreInput.value || '') : '';
        var desc = descInput ? (descInput.value || '') : '';
        var targetId = targetInput ? (targetInput.value || '') : '';
        var fileName = '';

        nombre = nombre.trim();

        if (fileInput && fileInput.files && fileInput.files.length) {
            fileName = fileInput.files[0].name || '';
        }

        if (!docLabel) {
            return false;
        }

        if (!nombre) {
            alert('Le nombre est obligatoire.');
            if (nombreInput) {
                nombreInput.focus();
            }
            return false;
        }

        if (!fileName) {
            alert('Le fichier est obligatoire.');
            return false;
        }

        if (targetId) {
            var targetField = btkGetEl(targetId);
            if (targetField) {
                targetField.value = fileName || docLabel;
            }
        }

        var fileKey = '';
        if (fileInput && fileInput.files && fileInput.files.length && filesContainer && fileSlot) {
            fileKey = 'docFile_' + (btkState().counter++);
            fileInput.setAttribute('name', fileKey);
            fileInput.setAttribute('id', fileKey);
            filesContainer.appendChild(fileInput);

            var newInput = document.createElement('input');
            newInput.type = 'file';
            newInput.id = 'dialogFile';
            newInput.name = 'dialogFile';
            newInput.setAttribute('style', 'position:absolute;left:-9999px;width:1px;height:1px;opacity:0;');
            newInput.addEventListener('change', function () { btkSyncDialogFile(newInput); });
            fileSlot.appendChild(newInput);

            var fileLabelEl = fileSlot.parentElement ? fileSlot.parentElement.querySelector('label[for]') : null;
            if (fileLabelEl) {
                fileLabelEl.setAttribute('for', 'dialogFile');
            }
        }

        btkDocs().push({
            label: docLabel,
            dateAjout: btkCurrentDateTime(),
            dateCreation: btkCurrentDateTime(),
            createdBy: btkResolveCreator(),
            copie: copie,
            nombre: nombre,
            desc: desc,
            file: fileName,
            fileKey: fileKey
        });

        btkRenderDocs();
        btkCloseUploadDialog();

        return false;
    }

    function btkHandleFormSubmit(event) {
        btkSyncDocsPayload();
        if (!btkIsUploadDialogOpen()) {
            return true;
        }

        return btkConfirmDialog(event);
    }

    function btkHandleDialogKeydown(event) {
        if (!event || !btkIsUploadDialogOpen()) {
            return true;
        }

        if (event.key !== 'Enter') {
            return true;
        }

        var target = event.target;
        if (target && target.tagName && target.tagName.toLowerCase() === 'textarea') {
            return true;
        }

        return btkConfirmDialog(event);
    }

    function btkRenderDocs() {
        var docs = btkDocs();
        var body = btkGetEl('docsBody');
        if (!body) {
            return;
        }

        var layout = btkDocsLayout();
        if (!docs.length) {
            var colspan = layout === 'actions' ? 4 : 7;
            body.innerHTML = '<tr id="docsEmptyRow"><td colspan="' + colspan + '" class="doc-empty">Aucun document ajouté.</td></tr>';
            btkSyncDocsPayload();
            return;
        }

        if (layout === 'actions') {
            body.innerHTML = docs.map(function (doc, i) {
                return '<tr>' +
                    '<td>' + btkEscapeHtml(doc.file || doc.label || '-') + '</td>' +
                    '<td>' + btkEscapeHtml(doc.dateCreation || doc.dateAjout || '-') + '</td>' +
                    '<td>' + btkEscapeHtml(doc.createdBy || '-') + '</td>' +
                    '<td><div class="doc-action-buttons">' +
                    '<button type="button" class="doc-action-btn" title="Télécharger" onclick="return btkDownloadDoc(' + i + ')">&#128229;</button>' +
                    '<button type="button" class="doc-action-btn" title="Visualiser" onclick="return btkShowDocInfo(' + i + ')">&#128065;</button>' +
                    '<button type="button" class="doc-action-btn doc-delete" title="Supprimer" onclick="return btkRemoveDoc(' + i + ')">&#128465;</button>' +
                    '</div></td>' +
                    '</tr>';
            }).join('');
        } else {
            body.innerHTML = docs.map(function (doc, i) {
                return '<tr>' +
                    '<td>' + btkEscapeHtml(doc.label || '-') + '</td>' +
                    '<td>' + btkEscapeHtml(doc.dateAjout || '-') + '</td>' +
                    '<td>' + btkEscapeHtml(doc.copie || '-') + '</td>' +
                    '<td>' + btkEscapeHtml(doc.nombre || '-') + '</td>' +
                    '<td>' + btkEscapeHtml(doc.desc || '-') + '</td>' +
                    '<td>' + btkEscapeHtml(doc.file || '-') + '</td>' +
                    '<td><button type="button" class="doc-delete" title="Supprimer" onclick="btkRemoveDoc(' + i + ')">&#128465;</button></td>' +
                    '</tr>';
            }).join('');
        }

        btkSyncDocsPayload();
    }

    function btkDownloadDoc(index) {
        var docs = btkDocs();
        if (index < 0 || index >= docs.length) {
            return false;
        }

        var doc = docs[index];
        var input = btkGetDocFileInput(doc);
        if (!input || !input.files || !input.files.length) {
            window.alert('Fichier introuvable pour ce document.');
            return false;
        }

        var file = input.files[0];
        var url = URL.createObjectURL(file);
        var a = document.createElement('a');
        a.href = url;
        a.download = doc.file || file.name || 'document';
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(function () {
            URL.revokeObjectURL(url);
        }, 1000);
        return false;
    }

    function btkSetInfoValue(id, value) {
        var node = btkGetEl(id);
        if (node) {
            node.textContent = value == null || value === '' ? '-' : String(value);
        }
    }

    function btkShowDocInfo(index) {
        var docs = btkDocs();
        if (index < 0 || index >= docs.length) {
            return false;
        }

        var doc = docs[index];
        btkSetInfoValue('docInfoNomDocument', doc.label || doc.file || '-');
        btkSetInfoValue('docInfoCopie', doc.copie || '-');
        btkSetInfoValue('docInfoNombreCopies', doc.nombre || '-');
        btkSetInfoValue('docInfoDescription', doc.desc || '-');

        var overlay = btkGetEl('docInfoOverlay');
        if (overlay) {
            overlay.classList.add('is-open');
            return false;
        }

        window.alert(
            'Nom du document : ' + (doc.label || doc.file || '-') + '\n' +
            'Copie: ' + (doc.copie || '-') + '\n' +
            'Nombre de copies: ' + (doc.nombre || '-') + '\n' +
            'Description: ' + (doc.desc || '-')
        );
        return false;
    }

    function btkCloseDocInfoDialog() {
        var overlay = btkGetEl('docInfoOverlay');
        if (overlay) {
            overlay.classList.remove('is-open');
        }
        return false;
    }

    function btkDocInfoOverlayClick(event) {
        if (!event) {
            return false;
        }
        if (event.target === btkGetEl('docInfoOverlay')) {
            return btkCloseDocInfoDialog();
        }
        return false;
    }

    function btkRemoveDoc(index) {
        var docs = btkDocs();
        if (index < 0 || index >= docs.length) {
            return false;
        }
        if (!window.confirm('Confirmer la suppression de ce document ?')) {
            return false;
        }
        var removed = docs[index];
        if (removed && removed.fileKey) {
            var filesContainer = btkGetEl('docsFilesContainer');
            if (filesContainer) {
                var input = filesContainer.querySelector('[name="' + removed.fileKey + '"]');
                if (input) {
                    input.remove();
                }
            }
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
    window.btkSyncDocsPayload = btkSyncDocsPayload;
    window.btkConfirmDialog = btkConfirmDialog;
    window.btkHandleFormSubmit = btkHandleFormSubmit;
    window.btkDownloadDoc = btkDownloadDoc;
    window.btkShowDocInfo = btkShowDocInfo;
    window.btkCloseDocInfoDialog = btkCloseDocInfoDialog;
    window.btkDocInfoOverlayClick = btkDocInfoOverlayClick;
    window.btkRemoveDoc = btkRemoveDoc;

    document.addEventListener('keydown', btkHandleDialogKeydown, true);
})();
