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
        if (e.target.classList.contains('focusable')) {
            const varName = e.target.getAttribute('data-varname');
            const uuid = e.target.getAttribute('data-uuid');
            const currentValue = e.target.textContent;

            // Create an input field
            const input = document.createElement('input');
            input.type = 'text';
            input.value = currentValue;
            input.className = 'focusable-input';

            // Replace the span with the input
            e.target.parentNode.replaceChild(input, e.target);
            input.focus();
            input.select();

            // Handle when input loses focus or Enter is pressed
            function handleUpdate() {
                const newValue = input.value;

                // Create new span with updated value
                const newSpan = document.createElement('span');
                newSpan.className = 'focusable';
                newSpan.setAttribute('data-varname', varName);
                newSpan.setAttribute('data-uuid', uuid);
                newSpan.textContent = newValue;

                // Replace input with updated span
                input.parentNode.replaceChild(newSpan, input);

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

            input.addEventListener('blur', handleUpdate);
            input.addEventListener('keypress', function(e) {
                if (e.key === 'Enter') {
                    input.blur();
                }
            });
        }
    });
});
