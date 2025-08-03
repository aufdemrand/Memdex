document.addEventListener('DOMContentLoaded', function() {
    // Handle back button clicks
    document.addEventListener('click', function(e) {
        if (e.target.classList.contains('back-button')) {
            e.preventDefault();
            const backSteps = parseInt(e.target.getAttribute('data-back')) || 1;

            // Save all select values on back_button click
            document.querySelectorAll('select.selectable').forEach(function(select) {
                const varName = select.getAttribute('data-varname');
                const uuid = select.getAttribute('data-uuid');
                const value = select.value;
                if (varName && uuid) {
                    fetch('/update', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ uuid: uuid, varName: varName, value: value })
                    });
                }
            });

            window.history.go(-backSteps);
        }
    });

    // Handle select dropdown changes
    document.addEventListener('change', function(e) {
        if (e.target.classList.contains('selectable')) {
            const varName = e.target.getAttribute('data-varname');
            const uuid = e.target.getAttribute('data-uuid');
            const value = e.target.value;

            if (varName && uuid) {
                // Send AJAX request to update the record
                fetch('/update', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        uuid: uuid,
                        varName: varName,
                        value: value
                    })
                })
                .then(response => response.json())
                .then(data => {
                    if (!data.success) {
                        console.error('Failed to update record:', data.error);
                        // Optionally revert the select value or show error to user
                    }
                })
                .catch(error => {
                    console.error('Error updating record:', error);
                });
            }
        }
    });

    // Handle focusable element clicks and updates
    document.addEventListener('click', function(e) {
        if (e.target.classList.contains('focusable') && e.target.contentEditable !== 'true') {
            const varName = e.target.getAttribute('data-varname');
            const uuid = e.target.getAttribute('data-uuid');
            const currentValue = e.target.textContent;

            // Make the element editable
            e.target.contentEditable = 'true';
            e.target.focus();

            // Select all text for easy editing
            const range = document.createRange();
            range.selectNodeContents(e.target);
            const selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);

            // Handle when element loses focus or Enter is pressed
            function handleUpdate() {
                const newValue = e.target.textContent.trim();

                // Make the element non-editable again
                e.target.contentEditable = 'false';

                // Send update to server if value changed
                if (newValue !== currentValue && varName && uuid) {
                    fetch('/update', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify({
                            uuid: uuid,
                            varName: varName,
                            value: newValue
                        })
                    })
                    .then(response => response.json())
                    .then(data => {
                        if (!data.success) {
                            console.error('Failed to update record:', data.error);
                        }
                    })
                    .catch(error => {
                        console.error('Error updating record:', error);
                    });
                }
            }

            e.target.addEventListener('blur', handleUpdate, { once: true });
            e.target.addEventListener('keypress', function(event) {
                if (event.key === 'Enter') {
                    event.preventDefault();
                    e.target.blur();
                }
            }, { once: true });
        }
    });
});
