(() => {
  const shell = document.querySelector(".admin-shell");

  document.querySelector("[data-sidebar-toggle]")?.addEventListener("click", () => {
    shell?.classList.toggle("is-sidebar-open");
  });

  document.querySelectorAll("[data-refresh-page]").forEach((button) => {
    button.addEventListener("click", () => window.location.reload());
  });

  const toastStack = document.createElement("div");
  toastStack.className = "toast-stack";
  document.body.appendChild(toastStack);

  const toast = (message, type = "success") => {
    const el = document.createElement("div");
    el.className = `toast toast--${type}`;
    el.textContent = message;
    toastStack.appendChild(el);
    window.setTimeout(() => el.remove(), 2400);
  };

  const formToJson = (form) => {
    const payload = {};
    new FormData(form).forEach((value, key) => {
      if (payload[key] !== undefined) {
        payload[key] = Array.isArray(payload[key]) ? [...payload[key], value] : [payload[key], value];
        return;
      }
      payload[key] = value;
    });
    form.querySelectorAll("input[type=checkbox]").forEach((input) => {
      if (!input.name) return;
      const sameNamed = form.querySelectorAll(`input[type="checkbox"][name="${input.name}"]`);
      if (sameNamed.length > 1) return;
      payload[input.name] = input.checked;
    });
    return payload;
  };

  document.querySelectorAll("form[data-api-form]").forEach((form) => {
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const method = form.dataset.method || form.method || "POST";
      const response = await fetch(form.action, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(formToJson(form)),
      });
      if (!response.ok) {
        let message = "요청 처리에 실패했습니다.";
        try {
          const payload = await response.json();
          message = payload.error || payload.detail || message;
        } catch {
          const text = await response.text();
          if (text) message = text;
        }
        toast(message, "error");
        return;
      }
      toast(form.dataset.successMessage || "요청이 완료되었습니다.");
      if (form.dataset.reload === "true") {
        window.location.reload();
      }
    });
  });
})();
