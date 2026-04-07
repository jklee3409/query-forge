(() => {
  const shell = document.querySelector(".admin-shell");
  const toastStack = document.createElement("div");
  toastStack.className = "toast-stack";
  document.body.appendChild(toastStack);

  const modalBackdrop = document.createElement("div");
  modalBackdrop.className = "modal-backdrop";
  modalBackdrop.innerHTML = `
    <div class="modal">
      <h3 class="modal__title">확인</h3>
      <div class="modal__body" data-modal-message></div>
      <div class="form-actions" style="margin-top:16px;">
        <button type="button" class="button button--ghost" data-modal-cancel>취소</button>
        <button type="button" class="button button--danger" data-modal-confirm>확인</button>
      </div>
    </div>
  `;
  document.body.appendChild(modalBackdrop);

  const showToast = (message, type = "success") => {
    const toast = document.createElement("div");
    toast.className = `toast toast--${type}`;
    toast.textContent = message;
    toastStack.appendChild(toast);
    window.setTimeout(() => toast.remove(), 3200);
  };

  const confirmAction = (message) =>
    new Promise((resolve) => {
      modalBackdrop.classList.add("is-open");
      modalBackdrop.querySelector("[data-modal-message]").textContent = message;
      const close = (accepted) => {
        modalBackdrop.classList.remove("is-open");
        confirmButton.replaceWith(confirmButton.cloneNode(true));
        cancelButton.replaceWith(cancelButton.cloneNode(true));
        resolve(accepted);
      };
      const confirmButton = modalBackdrop.querySelector("[data-modal-confirm]");
      const cancelButton = modalBackdrop.querySelector("[data-modal-cancel]");
      confirmButton.addEventListener("click", () => close(true), { once: true });
      cancelButton.addEventListener("click", () => close(false), { once: true });
    });

  document.querySelector("[data-sidebar-toggle]")?.addEventListener("click", () => {
    shell?.classList.toggle("is-sidebar-open");
  });

  document.querySelectorAll("[data-refresh-page]").forEach((button) => {
    button.addEventListener("click", () => {
      window.location.reload();
    });
  });

  document.querySelectorAll("[data-copy]").forEach((button) => {
    button.addEventListener("click", async () => {
      const target = button.getAttribute("data-copy");
      try {
        await navigator.clipboard.writeText(target);
        showToast("클립보드에 복사했습니다.");
      } catch (error) {
        showToast("복사에 실패했습니다.", "error");
      }
    });
  });

  document.querySelectorAll("form[data-persist-key]").forEach((form) => {
    const key = `query-forge:${form.dataset.persistKey}`;
    const stored = window.localStorage.getItem(key);
    if (stored) {
      const values = JSON.parse(stored);
      Object.entries(values).forEach(([name, value]) => {
        const field = form.elements.namedItem(name);
        if (!field) {
          return;
        }
        if (field instanceof RadioNodeList) {
          Array.from(field).forEach((node) => {
            if (node.value === value) {
              node.checked = true;
            }
          });
          return;
        }
        if (field.type === "checkbox") {
          field.checked = value === true || value === "true";
          return;
        }
        field.value = value;
      });
    }
    form.addEventListener("submit", () => {
      const payload = {};
      Array.from(form.elements).forEach((element) => {
        if (!element.name) {
          return;
        }
        payload[element.name] = element.type === "checkbox" ? element.checked : element.value;
      });
      window.localStorage.setItem(key, JSON.stringify(payload));
    });
  });

  const formToJson = (form) => {
    const payload = {};
    new FormData(form).forEach((value, key) => {
      if (payload[key] !== undefined) {
        payload[key] = Array.isArray(payload[key]) ? [...payload[key], value] : [payload[key], value];
        return;
      }
      payload[key] = value;
    });
    Object.keys(payload).forEach((key) => {
      if (key.endsWith("Ids") && !Array.isArray(payload[key])) {
        payload[key] = [payload[key]];
      }
    });
    form.querySelectorAll("input[type=checkbox]").forEach((checkbox) => {
      if (!checkbox.name) {
        return;
      }
      const sameNamed = form.querySelectorAll(`input[type="checkbox"][name="${checkbox.name}"]`);
      if (sameNamed.length > 1) {
        return;
      }
      payload[checkbox.name] = checkbox.checked;
    });
    return payload;
  };

  document.querySelectorAll("form[data-api-form]").forEach((form) => {
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (form.dataset.confirm) {
        const accepted = await confirmAction(form.dataset.confirm);
        if (!accepted) {
          return;
        }
      }

      const response = await fetch(form.action, {
        method: form.dataset.method || form.method || "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(formToJson(form)),
      });

      if (!response.ok) {
        let errorText = "요청에 실패했습니다.";
        try {
          const payload = await response.json();
          errorText = payload.error || errorText;
        } catch (error) {
          errorText = await response.text() || errorText;
        }
        showToast(errorText, "error");
        return;
      }

      let payload = {};
      try {
        payload = await response.json();
      } catch (error) {
        payload = {};
      }

      showToast(form.dataset.successMessage || "요청이 완료되었습니다.");

      const redirectTemplate = form.dataset.redirect;
      if (redirectTemplate) {
        const redirectUrl = redirectTemplate.replace("{runId}", payload.runId || payload.run_id || "");
        window.location.href = redirectUrl;
        return;
      }

      if (form.dataset.reload === "true") {
        window.location.reload();
      }
    });
  });

  document.querySelectorAll("[data-api-delete]").forEach((button) => {
    button.addEventListener("click", async () => {
      const accepted = await confirmAction(button.dataset.confirm || "정말 삭제하시겠습니까?");
      if (!accepted) {
        return;
      }
      const response = await fetch(button.dataset.apiDelete, { method: "DELETE" });
      if (!response.ok) {
        showToast("삭제 요청에 실패했습니다.", "error");
        return;
      }
      showToast("삭제했습니다.");
      if (button.dataset.reload === "true") {
        window.location.reload();
      }
    });
  });

  document.querySelectorAll("[data-sort-table]").forEach((table) => {
    table.querySelectorAll("th[data-sort-key]").forEach((header, index) => {
      header.style.cursor = "pointer";
      header.addEventListener("click", () => {
        const body = table.querySelector("tbody");
        const rows = Array.from(body.querySelectorAll("tr"));
        const asc = header.dataset.sortOrder !== "asc";
        header.dataset.sortOrder = asc ? "asc" : "desc";
        rows.sort((left, right) => {
          const leftValue = left.children[index]?.innerText.trim() || "";
          const rightValue = right.children[index]?.innerText.trim() || "";
          return asc ? leftValue.localeCompare(rightValue, "ko") : rightValue.localeCompare(leftValue, "ko");
        });
        rows.forEach((row) => body.appendChild(row));
      });
    });
  });

  document.querySelectorAll("[data-poll-url]").forEach((node) => {
    const pollUrl = node.dataset.pollUrl;
    window.setInterval(async () => {
      const response = await fetch(pollUrl);
      if (!response.ok) {
        return;
      }
      const payload = await response.json();
      if (Array.isArray(payload)) {
        if (payload.some((item) => item.runStatus === "running" || item.runStatus === "queued")) {
          window.location.reload();
        }
      } else if (payload.run && (payload.run.runStatus === "running" || payload.run.runStatus === "queued")) {
        window.location.reload();
      }
    }, 5000);
  });
})();
