"""Renders Minecraft entity boxes in Blender for the clothes previews (runs INSIDE Blender, headless).

Adapted from Voyager's tools/mcrender.py (its entity part: the box UV layout, the face corners, the
pixel-art texture material, the orthographic camera). The job file comes from tools/entity_preview.py and
already holds every box in model space (pixels, y down, front = -z) with its eight corners transformed and
its face UVs worked out, so this script only builds the quads, frames the camera, writes the labels and
renders. One job is one sheet.

Run:  blender -b --factory-startup --python tools/mcrender.py -- <job.json>
"""
import json
import math
import os
import sys

import bpy
from mathutils import Vector

# which box corner each UV corner maps to, per face; corners as (x, y, z) selectors (0 = min, 1 = max), y DOWN
ENTITY_UV_CORNERS = {
    "front": [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)],    # -z side: u ~ +x, v ~ +y(down)
    "back": [(1, 0, 1), (0, 0, 1), (0, 1, 1), (1, 1, 1)],     # +z side: u ~ -x
    "right": [(0, 0, 1), (0, 0, 0), (0, 1, 0), (0, 1, 1)],    # -x side: u ~ -z
    "left": [(1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)],     # +x side: u ~ +z
    "top": [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)],      # y min: u ~ +x, v ~ -z
    "bottom": [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],   # y max: u ~ +x, v ~ +z
}

_mat_cache = {}


def clear_scene():
    for obj in list(bpy.data.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
    for coll in (bpy.data.meshes, bpy.data.materials, bpy.data.images, bpy.data.lights, bpy.data.cameras, bpy.data.curves):
        for block in list(coll):
            if block.users == 0:
                coll.remove(block)
    _mat_cache.clear()


def texture_material(path):
    if path in _mat_cache:
        return _mat_cache[path]
    mat = bpy.data.materials.new("entity_tex")
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Roughness"].default_value = 0.85
    img = bpy.data.images.load(path, check_existing=True)
    img.colorspace_settings.name = "sRGB"
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = img
    tex.interpolation = "Closest"
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    try:
        mat.surface_render_method = "DITHERED"
    except Exception:
        mat.blend_method = "CLIP"
    mat.use_backface_culling = False
    _mat_cache[path] = mat
    return mat


def flat_material(name, rgb, emit=0.0):
    key = "flat:" + name
    if key in _mat_cache:
        return _mat_cache[key]
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (*rgb, 1.0)
    bsdf.inputs["Roughness"].default_value = 1.0
    if emit:
        bsdf.inputs["Emission Color"].default_value = (*rgb, 1.0)
        bsdf.inputs["Emission Strength"].default_value = emit
    _mat_cache[key] = mat
    return mat


def to_blender(p, dx):
    """Model space (pixels, y down, front -z) to Blender metres (z up, front -y), feet of an adult at z = 0."""
    return Vector(((p[0] + dx) / 16.0, p[2] / 16.0, (24.0 - p[1]) / 16.0))


def build_box(box, dx, scene):
    mat = texture_material(box["tex"])
    corners = box["corners"]   # 8 points, index = xi * 4 + yi * 2 + zi
    verts, faces, uvs = [], [], []
    quads = []
    for face, sel in ENTITY_UV_CORNERS.items():
        pts = [to_blender(corners[s[0] * 4 + s[1] * 2 + s[2]], dx) for s in sel]
        base = len(verts)
        verts.extend(tuple(p) for p in pts)
        faces.append((base, base + 1, base + 2, base + 3))
        uvs.append([tuple(uv) for uv in box["uv"][face]])
        quads.append(pts)
    centre = sum((p for q in quads for p in q), Vector()) / 24.0
    for i, pts in enumerate(quads):
        normal = (pts[1] - pts[0]).cross(pts[2] - pts[0])
        if normal.length > 1e-9 and normal.dot((pts[0] + pts[2]) / 2 - centre) < 0:
            b = faces[i][0]
            faces[i] = (b + 3, b + 2, b + 1, b)
            uvs[i] = uvs[i][::-1]
    mesh = bpy.data.meshes.new(box.get("name", "box"))
    mesh.from_pydata(verts, [], faces)
    mesh.materials.append(mat)
    layer = mesh.uv_layers.new(name="UVMap")
    for poly, quad in zip(mesh.polygons, uvs):
        for li, uv in zip(poly.loop_indices, quad):
            layer.data[li].uv = uv
    mesh.update()
    obj = bpy.data.objects.new(mesh.name, mesh)
    scene.collection.objects.link(obj)
    return obj


def add_label(text, x, z, size, scene, yaw, pitch, rgb=(0.92, 0.92, 0.92)):
    curve = bpy.data.curves.new("label", "FONT")
    curve.body = text
    curve.align_x = "CENTER"
    curve.align_y = "TOP"
    curve.size = size
    obj = bpy.data.objects.new("label", curve)
    # turned to face the camera and moved straight towards it: drawn where it stands, in front of every figure
    toward = Vector((math.sin(yaw) * math.cos(pitch), -math.cos(yaw) * math.cos(pitch), math.sin(pitch))) * 1.5
    obj.location = Vector((x, 0.0, z)) + toward
    obj.rotation_euler = (math.radians(90) - pitch, 0, yaw)
    obj.data.materials.append(flat_material("label_" + text[:8], rgb, emit=1.5))
    scene.collection.objects.link(obj)
    return obj


def render(job):
    clear_scene()
    scene = bpy.context.scene
    figures = job["figures"]
    spacing = job.get("spacing", 1.3)
    columns = job.get("columns", len(figures))
    row_height = job.get("row_height", 2.6)
    xs, zs = [], []
    for i, fig in enumerate(figures):
        col, row = i % columns, i // columns
        dx = col * spacing * 16
        dz = -row * row_height
        objs = []
        for box in fig["boxes"]:
            obj = build_box(box, dx, scene)
            obj.location.z += dz
            objs.append(obj)
        if fig.get("label"):
            add_label(fig["label"], dx / 16.0, dz + job.get("label_z", -0.1), job.get("label_size", 0.16), scene,
                      math.radians(job.get("yaw", 25.0)), math.radians(job.get("pitch", 10.0)))
        lo, hi = job.get("frame", (-0.55, 2.35))     # the height band to frame: whole figures, or a close-up
        xs += [dx / 16.0 - 0.7, dx / 16.0 + 0.7]
        zs += [dz + lo, dz + hi]
    # light: a warm key from the front left, a cool fill, a little world light
    for name, energy, rot in (("key", 3.2, (50, 0, -35)), ("fill", 1.1, (65, 0, 140))):
        data = bpy.data.lights.new(name, "SUN")
        data.energy = energy
        data.angle = math.radians(6)
        sun = bpy.data.objects.new(name, data)
        sun.rotation_euler = tuple(math.radians(a) for a in rot)
        scene.collection.objects.link(sun)
    world = bpy.data.worlds.get("World") or bpy.data.worlds.new("World")
    scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    if bg:
        bg.inputs["Color"].default_value = tuple(job.get("background", (0.15, 0.16, 0.20))) + (1.0,)
        bg.inputs["Strength"].default_value = 0.8
    # orthographic camera turned by yaw (degrees, positive = from the figure's left) and pitched down
    yaw = math.radians(job.get("yaw", 25.0))
    pitch = math.radians(job.get("pitch", 10.0))
    cx = (min(xs) + max(xs)) / 2
    cz = (min(zs) + max(zs)) / 2
    width = max(xs) - min(xs)
    height = max(zs) - min(zs)
    direction = Vector((math.sin(yaw) * math.cos(pitch), -math.cos(yaw) * math.cos(pitch), math.sin(pitch)))
    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = "ORTHO"
    res_x, res_y = job.get("size", (1000, 700))
    aspect = res_x / res_y
    cam_data.ortho_scale = max(width, height * aspect) * job.get("zoom", 1.05)
    cam = bpy.data.objects.new("cam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    cam.location = Vector((cx, 0.0, cz)) + direction * 40.0
    cam.rotation_euler = (-direction).to_track_quat("-Z", "Y").to_euler()
    engines = [i.identifier for i in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items]
    scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in engines else "BLENDER_EEVEE"
    scene.render.resolution_x = res_x
    scene.render.resolution_y = res_y
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = False
    try:
        scene.eevee.taa_render_samples = 32
    except Exception:
        pass
    try:
        scene.view_settings.view_transform = "Standard"
    except Exception:
        pass
    out = job["out"]
    os.makedirs(os.path.dirname(out), exist_ok=True)
    scene.render.filepath = out
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.render.image_settings.compression = 90
    bpy.ops.render.render(write_still=True)
    return out


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    with open(argv[0], encoding="utf-8") as f:
        jobs = json.load(f)
    for job in jobs:
        print("rendered", render(job))


if __name__ == "__main__":
    main()
