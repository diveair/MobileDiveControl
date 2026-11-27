"""Desktop controls for the wired DiveControl Depth Lab."""
import queue
import time
import tkinter as tk
from tkinter import ttk

from controller import Controller

BG, PANEL, TEXT, MUTED, AQUA = "#102127", "#183139", "#f0f5f4", "#afc4c6", "#6de4c7"


class DepthDesk:
    def __init__(self, root):
        self.root = root
        self.link = Controller()
        self.depth = 0.0
        self.direction = 0
        self.last_tick = time.monotonic()
        self.sequence = []
        self.connected = False
        self.busy = False
        self.closing = False
        self.programmatic_slider = False
        self.status = tk.StringVar(value="Connect the S25 by USB to start.")
        self.target = tk.StringVar(value="0.00")
        self.live = tk.StringVar(value="—")
        self.profile = tk.StringVar(value="Waiting for live dive readings")
        self.radio = tk.StringVar(value="Bluetooth housing · Waiting for phone")
        self.use_housing = tk.BooleanVar(value=True)
        self.link.set_depth(0, housing_depth=True)
        self.motion = tk.StringVar(value="Holding")
        self.rate = tk.StringVar(value="6")
        self.lost = tk.BooleanVar()
        self.connect_text = tk.StringVar(value="Connect via USB")
        root.title("DiveControl · USB Depth Lab")
        root.configure(bg=BG)
        root.minsize(640, 550)
        style = ttk.Style()
        style.theme_use("clam")
        style.configure(".", font=("Segoe UI", 10), background=BG, foreground=TEXT)
        style.configure("TButton", background=PANEL, foreground=TEXT, padding=(11, 7), borderwidth=1)
        style.map("TButton", background=[("active", "#285059"), ("pressed", "#31636a")],
                  foreground=[("disabled", "#819395")])
        style.configure("Accent.TButton", background=AQUA, foreground=BG)
        style.map("Accent.TButton", background=[("active", "#94efdc")], foreground=[("disabled", "#607374")])
        style.configure("TCheckbutton", background=BG, foreground=TEXT)
        style.map("TCheckbutton", background=[("active", BG)])
        style.configure("TEntry", fieldbackground="#fbffff", foreground="#102127", padding=5)
        style.configure("TScale", background=BG, troughcolor=PANEL)
        body = tk.Frame(root, bg=BG, padx=22, pady=16)
        body.pack(fill="both", expand=True)
        body.columnconfigure(0, weight=1)
        self.body = body
        header = self.row(0)
        self.label(header, "Depth Lab", 23, bold=True).pack(side="left")
        self.connect_button = ttk.Button(header, textvariable=self.connect_text, style="Accent.TButton", command=self.connect)
        self.connect_button.pack(side="right")
        self.install = ttk.Button(header, text="Update lab APK", command=lambda: self.connect(True))
        self.install.pack(side="right", padx=6)
        self.label(body, self.status, 10, color=MUTED, wrap=550).grid(row=1, column=0, sticky="w", pady=(0, 12))
        readout = tk.Frame(body, bg=PANEL, padx=14, pady=8)
        readout.grid(row=2, column=0, sticky="ew")
        self.label(readout, "Depth received by phone", 10, color=MUTED).pack(side="left")
        self.label(readout, self.live, 27, color=AQUA, bold=True).pack(side="left", padx=12)
        self.label(readout, self.motion, 11).pack(side="right")

        exact = self.row(3, (14, 2))
        self.label(exact, "Set depth (m)", 11, bold=True).pack(side="left")
        entry = ttk.Entry(exact, width=8, textvariable=self.target, justify="right")
        entry.pack(side="left", padx=8)
        entry.bind("<Return>", lambda _: self.apply())
        ttk.Button(exact, text="Set", command=self.apply).pack(side="left")
        for delta in (-0.1, 0.1, -0.01, 0.01):
            ttk.Button(exact, text=f"{delta:+g}", width=5, command=lambda d=delta: self.jump(self.depth + d)).pack(side="left", padx=(4, 0))
        self.slider = ttk.Scale(body, from_=0, to=30, command=self.slide)
        self.slider.grid(row=4, column=0, sticky="ew", pady=6)

        presets = self.row(5)
        self.label(presets, "Jump to", 10, color=MUTED).pack(side="left", padx=(0, 8))
        for depth in (0, 3.5, 4.5, 5, 5.5, 6.5, 7, 12):
            ttk.Button(presets, text=f"{depth:g} m", width=5, command=lambda d=depth: self.jump(d)).pack(side="left", padx=(0, 4))

        ramp = self.row(6, (16, 5))
        self.label(ramp, "Travel rate", 11, bold=True).pack(side="left")
        ttk.Entry(ramp, width=5, textvariable=self.rate, justify="right").pack(side="left", padx=(8, 3))
        self.label(ramp, "m/min", 10, color=MUTED).pack(side="left", padx=(0, 10))
        for label, direction in (("↑  Ascend", -1), ("Hold", 0), ("↓  Descend", 1)):
            ttk.Button(ramp, text=label, command=lambda d=direction: self.travel(d)).pack(side="left", padx=2)

        actions = self.row(7, (12, 5))
        self.prime = ttk.Button(actions, text="Start a 5 m stop", command=self.prime_stop)
        self.prime.pack(side="left", padx=(0, 8))
        self.gap = ttk.Checkbutton(actions, text="Simulate depth loss", variable=self.lost, command=self.select_gap)
        self.gap.pack(side="left")
        self.hardware = ttk.Checkbutton(actions, text="Use real housing depth", variable=self.use_housing, command=self.select_housing)
        self.hardware.pack(side="left", padx=6)
        pad = self.row(8, (11, 5))
        self.label(pad, "Housing", 11, bold=True).pack(side="left", padx=(0, 8))
        self.keys = []
        for label, key in (("OK / MENU", 0x50), ("Back", 0x60), ("←", 0x40), ("↑", 0x30), ("↓", 0x61), ("→", 0x10)):
            button = ttk.Button(pad, text=label, width=9 if key == 0x50 else 4,
                                command=lambda k=key: self.link.keys.put(k))
            button.pack(side="left", padx=2)
            self.keys.append(button)
        self.label(body, self.profile, 11, color=AQUA, wrap=590).grid(row=9, column=0, sticky="w", pady=(8, 0))
        self.label(body, self.radio, 10, color=MUTED, wrap=590).grid(row=10, column=0, sticky="w", pady=(4, 0))
        self.label(body, "Start a 5 m stop replays a qualifying descent to 12 m and an ascent to the target. "
                   "Press OK / MENU to minimize, then change depth or start a gradual ascent or descent.",
                   10, color=MUTED, wrap=590).grid(row=11, column=0, sticky="w", pady=(10, 0))
        self.controls()
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(60, self.tick)

    def row(self, index, pady=0):
        frame = tk.Frame(self.body, bg=BG)
        frame.grid(row=index, column=0, sticky="ew", pady=pady)
        return frame

    @staticmethod
    def label(parent, value, size, color=TEXT, bold=False, wrap=0):
        attrs = {"textvariable": value} if isinstance(value, tk.Variable) else {"text": value}
        return tk.Label(parent, **attrs, bg=parent["bg"], fg=color, justify="left", wraplength=wrap,
                        font=("Segoe UI", size, "bold" if bold else "normal"))

    def controls(self):
        for widget in [self.prime, self.gap, self.hardware, *self.keys]:
            widget.state(["!disabled" if self.connected else "disabled"])
        self.install.state(["disabled" if self.busy else "!disabled"])
        self.connect_text.set("Disconnect" if self.busy else "Connect via USB")

    def connect(self, reinstall=False):
        if self.busy:
            self.link.stop.set()
            self.status.set("Disconnecting…")
        else:
            self.busy = True
            self.status.set("Checking USB connection…")
            self.link.start(reinstall)
        self.controls()

    def apply(self):
        try:
            value = float(self.target.get())
            if not 0 <= value <= 90:
                raise ValueError()
        except ValueError:
            self.status.set("Enter a depth between 0 and 90 m.")
            return
        self.jump(value)

    def slide(self, value):
        if not self.programmatic_slider:
            self.jump(round(float(value), 1), move_slider=False)

    def set_depth(self, depth, move_slider=True):
        self.depth = min(90.0, max(0.0, depth))
        self.target.set(f"{self.depth:.2f}")
        if move_slider:
            self.programmatic_slider = True
            self.slider.set(min(30.0, self.depth))
            self.programmatic_slider = False
        self.link.set_depth(self.depth, self.lost.get(), self.use_housing.get())

    def jump(self, depth, move_slider=True):
        self.sequence.clear()
        self.direction = 0
        self.use_housing.set(False)
        self.lost.set(False)
        self.motion.set("Holding")
        self.set_depth(depth, move_slider)

    def travel(self, direction):
        if not self.connected and direction:
            self.status.set("Connect via USB before starting a depth ramp.")
            return
        try:
            speed = float(self.rate.get())
            if not 0 < speed <= 120:
                raise ValueError()
        except ValueError:
            if direction:
                self.status.set("Enter a travel rate greater than zero and at most 120 m/min.")
                return
            speed = 0
        self.sequence.clear()
        self.use_housing.set(False)
        self.lost.set(False)
        self.link.set_depth(self.depth)
        self.speed = speed
        self.direction = direction
        self.motion.set({-1: "Ascending", 0: "Holding", 1: "Descending"}[direction])
        self.last_tick = time.monotonic()

    def prime_stop(self):
        self.jump(0)
        start = time.monotonic()
        self.sequence = [(start + t, d) for t, d in ((1, 2), (2, 12), (4, 7), (5, 5))]
        self.motion.set("Replaying stop entry")

    def select_gap(self):
        self.use_housing.set(False)
        self.change_source()

    def select_housing(self):
        self.lost.set(False)
        self.change_source()

    def change_source(self):
        self.sequence.clear()
        self.direction = 0
        self.motion.set("Housing" if self.use_housing.get() else "Depth loss" if self.lost.get() else "Holding")
        self.link.set_depth(self.depth, self.lost.get(), self.use_housing.get())

    def tick(self):
        now = time.monotonic()
        elapsed, self.last_tick = now - self.last_tick, now
        while True:
            try:
                kind, value = self.link.events.get_nowait()
            except queue.Empty:
                break
            if kind in ("status", "error"):
                self.status.set(value)
            elif kind == "connected":
                self.connected = True
                self.status.set("USB connected · " + value)
            elif kind == "sample":
                depth = value.get("depth")
                self.live.set("No reading" if depth is None else f"{depth:.2f} m")
                raw = value.get("housingDepth")
                housing_depth = "No fresh reading" if raw is None else f"{raw:.2f} m"
                self.radio.set(f'Bluetooth housing · {value.get("housing", "Waiting")}   |   Housing sensor · {housing_depth}'
                               f'   |   Depth source · {value.get("source", "Waiting")}')
                phase = {"TooDeep": "Too deep", "TooShallow": "Too shallow"}.get(value.get("phase"), value.get("phase", "Surface"))
                fields = [phase]
                if "remaining" in value:
                    minutes, seconds = divmod(value["remaining"], 60)
                    fields.extend([value["presentation"], f"{minutes}:{seconds:02d} remaining"])
                if "rate" in value:
                    fields.append(f'{value["rate"]:+.1f} m/min (+ down)')
                if value.get("pending"):
                    fields.append("Reserve confirmation pending")
                elif value.get("confirmations"):
                    fields.append(f'Confirmed by diver ×{value["confirmations"]}')
                self.profile.set(" · ".join(fields))
            elif kind == "closed":
                if self.link.stop.is_set():
                    self.status.set("Disconnected")
                self.connected = self.busy = False
                self.live.set("—")
                self.profile.set("Waiting for live dive readings")
                self.radio.set("Bluetooth housing · Waiting for phone")
                self.lost.set(False)
                self.use_housing.set(True)
                self.change_source()
            self.controls()
        if self.connected and not self.lost.get():
            if self.sequence and now >= self.sequence[0][0]:
                _, depth = self.sequence.pop(0)
                self.set_depth(depth)
                if not self.sequence:
                    self.motion.set("Holding")
            elif self.direction:
                self.set_depth(self.depth + self.direction * self.speed * min(elapsed, 0.5) / 60)
                if self.depth in (0, 90):
                    self.travel(0)
        if self.closing and not self.busy:
            self.root.destroy()
        else:
            self.root.after(60, self.tick)

    def close(self):
        self.closing = True
        self.link.stop.set()
        if self.busy:
            self.status.set("Closing USB connection…")
        else:
            self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    DepthDesk(root)
    root.mainloop()
